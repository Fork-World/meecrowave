/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.meecrowave.junit;

import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.internal.ClassLoaderLock;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.config.WebBeansFinder;
import org.apache.webbeans.corespi.DefaultSingletonService;
import org.apache.webbeans.spi.SingletonService;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.InjectionTarget;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.stream.Collectors.toList;

public abstract class MeecrowaveRuleBase<T extends MeecrowaveRuleBase> implements TestRule {
    private final Collection<Object> toInject = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ClassLoader meecrowaveCL;

    @Override
    public Statement apply(final Statement base, final Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                final Thread thread = Thread.currentThread();
                ClassLoader oldCL = thread.getContextClassLoader();
                ClassLoaderLock.LOCK.lock();
                boolean unlocked = false;
                try {
                    ClassLoader newCl = getClassLoader();
                    if (newCl != null) {
                        thread.setContextClassLoader(newCl);
                    }
                    try (final AutoCloseable closeable = onStart()) {
                        ClassLoaderLock.LOCK.unlock();
                        unlocked = true;

                        started.set(true);
                        final Collection<CreationalContext<?>> contexts = toInject.stream()
                                                                                  .map(MeecrowaveRuleBase::doInject)
                                                                                  .collect(toList());
                        try {
                            base.evaluate();
                        } finally {
                            contexts.forEach(CreationalContext::release);
                            started.set(false);
                        }
                    } finally {
                        thread.setContextClassLoader(oldCL);
                    }
                } finally {
                    if (!unlocked) {
                        ClassLoaderLock.LOCK.unlock();
                    }
                }
            }
        };
    }

    private static CreationalContext<Object> doInject(final Object instance) {
        final BeanManager bm = CDI.current().getBeanManager();
        final AnnotatedType<?> annotatedType = bm.createAnnotatedType(instance.getClass());
        final InjectionTarget injectionTarget = bm.createInjectionTarget(annotatedType);
        final CreationalContext<Object> creationalContext = bm.createCreationalContext(null);
        injectionTarget.inject(instance, creationalContext);
        return creationalContext;
    }

    public <T> T inject(final Object instance) {
        if (started.get()) {
            doInject(instance); // TODO: store cc to release it
        } else {
            toInject.add(instance);
        }
        return (T) this;
    }

    public abstract Meecrowave.Builder getConfiguration();

    protected abstract AutoCloseable onStart();

    protected ClassLoader getClassLoader() {
        if (meecrowaveCL == null) {
            ClassLoader currentCL = Thread.currentThread().getContextClassLoader();
            if (currentCL == null) {
                currentCL = this.getClass().getClassLoader();
            }

            final SingletonService<WebBeansContext> singletonService = WebBeansFinder.getSingletonService();
            synchronized (singletonService) {
                try {
                    if (singletonService instanceof DefaultSingletonService) {
                        synchronized (singletonService) {
                            ((DefaultSingletonService) singletonService).register(currentCL, null);
                            // all fine, it seems we do not have an OWB container for this ClassLoader yet

                            // let's reset it then ;
                            ((DefaultSingletonService) singletonService).clear(currentCL);
                        }
                        meecrowaveCL = currentCL;
                    }
                }
                catch (IllegalArgumentException iae) {
                    // whoops there is already an OWB container registered for this very ClassLoader
                }

                if (meecrowaveCL == null) {
                    meecrowaveCL = new ClassLoader(currentCL) {};
                }
            }
        }
        return meecrowaveCL;
    }
}
