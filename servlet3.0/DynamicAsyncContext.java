package com.baozun.store.web.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.alibaba.fastjson.JSON;

public class DynamicAsyncContext implements InitializingBean {
    
    private final static Logger LOG = LoggerFactory.getLogger(DynamicAsyncContext.class);
    
    private String poolSize; // 初始线程-最大线程
    
    private Integer asyncTimeoutInSeconds;// AsynContext失效时间
    
    private Integer keepAliveTimeInSeconds; // 等待新任务最大时间
    
    private Integer queueCapacity; // LinkedBlockingDeque容量
    
    /**
     * @return the poolSize
     */
    public String getPoolSize() {
        return poolSize;
    }

    /**
     * @param poolSize the poolSize to set
     */
    public void setPoolSize(String poolSize) {
        this.poolSize = poolSize;
    }

    /**
     * @return the asyncTimeoutInSeconds
     */
    public Integer getAsyncTimeoutInSeconds() {
        return asyncTimeoutInSeconds;
    }

    /**
     * @param asyncTimeoutInSeconds the asyncTimeoutInSeconds to set
     */
    public void setAsyncTimeoutInSeconds(Integer asyncTimeoutInSeconds) {
        this.asyncTimeoutInSeconds = asyncTimeoutInSeconds;
    }

    /**
     * @return the keepAliveTimeInSeconds
     */
    public Integer getKeepAliveTimeInSeconds() {
        return keepAliveTimeInSeconds;
    }

    /**
     * @param keepAliveTimeInSeconds the keepAliveTimeInSeconds to set
     */
    public void setKeepAliveTimeInSeconds(Integer keepAliveTimeInSeconds) {
        this.keepAliveTimeInSeconds = keepAliveTimeInSeconds;
    }

    /**
     * @return the queueCapacity
     */
    public Integer getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * @param queueCapacity the queueCapacity to set
     */
    public void setQueueCapacity(Integer queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    private BlockingDeque<Runnable> queue =null; // 队列
    private ThreadPoolExecutor executor= null;  // 线程池
    private AsyncListener asyncListener = null; // 异步监听器
    
    
    public void submitFuture(final HttpServletRequest req, final Callable<Object> task) {
        final String uri = req.getRequestURI();
        final Map<String, String[]> params = req.getParameterMap();  
        final AsyncContext asyncContext = req.startAsync();  // 开启异步上下文  
        asyncContext.getRequest().setAttribute("uri", uri);  
        asyncContext.getRequest().setAttribute("params", params);  
        asyncContext.setTimeout(asyncTimeoutInSeconds * 1000);  
        if(asyncListener != null) {
            asyncContext.addListener(asyncListener);  
        }
        
        executor.submit(new CanceledCallable(asyncContext) { // 提交任务给业务线程池
            
            @Override
            public Object call() throws Exception {
                Object o = task.call();  // 业务处理调用
                if(o == null) {  
                    callBack(this.getAsyncContext(), o, uri, params);  // 业务完成后，响应处理  
                }if(o instanceof CompletionService) {  
                    try {
                        CompletionService<Object> completionService = new ExecutorCompletionService<Object>(executor); // 异步任务线程池
                        completionService.submit(task);
                        Object resultObject = completionService.take().get();
                        callBack(this.getAsyncContext(), resultObject, uri, params);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callBack(this.getAsyncContext(), "", uri, params);  
                        return null;  
                    }
                }else if(o instanceof String) {  
                    callBack(this.getAsyncContext(), o, uri, params);  
                }
                return null;
            }
        } );
    }
    
    private void callBack(AsyncContext asyncContext, Object result, String uri, Map<String, String[]> params) {
        HttpServletResponse resp = (HttpServletResponse) asyncContext.getResponse();  
        try {  
            if(result instanceof String) {  
                resp.sendRedirect((String)result);
            } else {  
                write(resp, JSON.toJSONString(result));  
            }  
        } catch (Throwable e) {  
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); //程序内部错误  
            try {  
                LOG.error("get info error, uri : {},  params : {}", uri, JSON.toJSON(params), e);  
            } catch (Exception ex) {  
            }  
        } finally {  
            asyncContext.complete();  
        }  
    }
    
    private void write(HttpServletResponse resp, String result){
        PrintWriter writer = null;
        try {
            writer = resp.getWriter();
            writer.write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            writer.close();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
      String[] poolSizes = poolSize.split("-");
      //初始线程池大小
      int corePoolSize = Integer.valueOf(poolSizes[0]);
      //最大线程池大小
      int maximumPoolSize = Integer.valueOf(poolSizes[1]);
      queue = new LinkedBlockingDeque<Runnable>(queueCapacity);
      executor = new ThreadPoolExecutor(
      corePoolSize, maximumPoolSize,
      keepAliveTimeInSeconds, TimeUnit.SECONDS,
      queue);
      
      executor.allowCoreThreadTimeOut(true);
      executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if(r instanceof CanceledCallable) {
                CanceledCallable cc = ((CanceledCallable) r);
                AsyncContext asyncContext = cc.getAsyncContext();
                if(asyncContext != null) {
                    try {
                            String uri = (String) asyncContext.getRequest().getAttribute("uri");
                            Object params = asyncContext.getRequest().getAttribute("params");
                            LOG.error("async request rejected, uri : {}, params : {}", uri, JSON.toJSON(params));
                        } catch (Exception e) {}
                    try {
                            HttpServletResponse resp = (HttpServletResponse) asyncContext.getResponse();
                            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        } finally {
                            asyncContext.complete();
                        }
                }
            }
        }
      });
      if(asyncListener == null) { 
          asyncListener = new AsyncListener() {

            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                
            }

            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
                AsyncContext asyncContext = event.getAsyncContext(); 
                try {  
                    String uri = (String) asyncContext.getRequest().getAttribute("uri");  
                    Object params = asyncContext.getRequest().getAttribute("params");  
                    LOG.error("async request timeout, uri : {}, params : {}", uri, JSON.toJSON(params));  
                } catch (Exception e) {}
                try {  
                    HttpServletResponse resp = (HttpServletResponse) asyncContext.getResponse();  
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);  
                } finally {  
                    asyncContext.complete();  
                } 
            }

            @Override
            public void onError(AsyncEvent event) throws IOException {
                AsyncContext asyncContext = event.getAsyncContext();  
                try {  
                    String uri = (String) asyncContext.getRequest().getAttribute("uri");  
                    Object params = asyncContext.getRequest().getAttribute("params");  
                    LOG.error("async request error, uri : {}, params : {}", uri, JSON.toJSON(params));  
                } catch (Exception e) {}  
                try {  
                    HttpServletResponse resp = (HttpServletResponse) asyncContext.getResponse();  
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);  
                } finally {  
                    asyncContext.complete();  
                }  
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
                
            }};
      }
    }
    
    public void stop(){
        queue.clear();
        executor.shutdown();
    }
}
