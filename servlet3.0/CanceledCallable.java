package com.baozun.store.web.util;

import java.util.concurrent.Callable;

import javax.servlet.AsyncContext;

public  abstract class CanceledCallable implements Callable<Object> {
    
    private AsyncContext asyncContext;

    /**
     * @return the asyncContext
     */
    public AsyncContext getAsyncContext() {
        return asyncContext;
    }

    /**
     * @param asyncContext the asyncContext to set
     */
    public void setAsyncContext(AsyncContext asyncContext) {
        this.asyncContext = asyncContext;
    }

    public CanceledCallable() {
        super();
    }
    
    public CanceledCallable(AsyncContext asyncContext) {
        this.asyncContext = asyncContext;
    }
}
