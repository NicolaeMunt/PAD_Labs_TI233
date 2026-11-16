package md.utm.demo.common;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component 
public class InstanceIdFilter extends OncePerRequestFilter {

    private final String instanceId;

    public InstanceIdFilter(@Value("${app.instance-id:unknown}") String instanceId) {
        this.instanceId = instanceId;
    }

     @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Instance-Id", instanceId);
        chain.doFilter(request, response);
    }

    
}