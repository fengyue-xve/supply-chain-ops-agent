package com.demo.flowagent.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Reset is exclusive across the full HTTP transaction, including in-flight apply requests. */
@Component
public class DemoResetGate extends OncePerRequestFilter {
    private final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var lock="/api/flow/demo/reset".equals(request.getRequestURI())?gate.writeLock():gate.readLock();
        boolean locked=false;
        try {
            locked=lock.tryLock(3,TimeUnit.SECONDS);
            if (!locked) { response.setStatus(409); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"demo_busy\"}"); return; }
            chain.doFilter(request,response);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); response.setStatus(503); }
        finally { if(locked) lock.unlock(); }
    }
}
