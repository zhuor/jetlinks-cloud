package org.jetlinks.cloud;

import lombok.extern.slf4j.Slf4j;
import org.hswebframework.web.ThreadLocalUtils;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.token.UserToken;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @author zhouhao
 * @since 1.0.0
 */
@Configuration
@EnableAsync
@Slf4j
public class ExecutorServiceConfiguration {

    @Bean
    public ThreadFactory threadFactory() {
        return new SharedThreadLocalThreadFactory();
    }

    @Bean
    public AsyncConfigurer asyncConfigurer() {
        AsyncUncaughtExceptionHandler handler = new SimpleAsyncUncaughtExceptionHandler();

        return new AsyncConfigurer() {
            @Override
            public Executor getAsyncExecutor() {
                return threadPoolTaskExecutor().getObject();
            }

            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return handler;
            }
        };
    }

    @Bean
    public ScheduledExecutorService scheduledExecutorService() {
        return Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    }

    @Bean
    @ConfigurationProperties(prefix = "jetlinks.executor")
    @Primary
    public ThreadPoolExecutorFactoryBean threadPoolTaskExecutor() {
        ThreadPoolExecutorFactoryBean executor = new ThreadPoolExecutorFactoryBean() {
            @Override
            protected ThreadPoolExecutor createExecutor(int corePoolSize, int maxPoolSize, int keepAliveSeconds, BlockingQueue<Runnable> queue, ThreadFactory threadFactory, RejectedExecutionHandler rejectedExecutionHandler) {

                return new ThreadPoolExecutor(corePoolSize, maxPoolSize,
                        keepAliveSeconds, TimeUnit.SECONDS, queue, threadFactory, rejectedExecutionHandler) {
                    @Override
                    public void execute(Runnable command) {
                        Map<String, Object> objectMap = ThreadLocalUtils.getAll()
                                .entrySet()
                                .stream()
                                .filter(entry -> entry.getValue() != null &&
                                        (entry.getValue() instanceof String
                                                || entry.getValue() instanceof UserToken
                                                || entry.getValue() instanceof Authentication
                                                || entry.getValue().getClass().isPrimitive()
                                                || entry.getValue().getClass().isEnum())
                                ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                        super.execute(() -> {
                            if (logger.isInfoEnabled() && !objectMap.isEmpty()) {
                                log.info("share thread local info : {}", objectMap);
                            }
                            ThreadLocalUtils.clear();
                            objectMap.forEach(ThreadLocalUtils::put);
                            objectMap.clear();
                            try {
                                command.run();
                            } finally {
                                ThreadLocalUtils.clear();
                            }
                        });
                    }
                };
            }
        };
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        executor.setThreadFactory(threadFactory());
        return executor;
    }

    class SharedThreadLocalThreadFactory implements ThreadFactory {
        AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("jetlinks-thread-" + index.getAndAdd(1));
            thread.setContextClassLoader(this.getClass().getClassLoader());
            thread.setDaemon(false);
            return thread;
        }
    }
}
