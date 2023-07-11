/**
 * Copyright 2014 Nirmata, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nirmata.workflow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;


public class ThreadUtils
{

    public static boolean checkInterrupted(Throwable e)
    {
        if ( e instanceof InterruptedException )
        {
            Thread.currentThread().interrupt();
            return true;
        }
        return false;
    }

    public static ExecutorService newSingleThreadExecutor(String processName)
    {
        return Executors.newSingleThreadExecutor(newThreadFactory(processName));
    }

    public static ExecutorService newFixedThreadPool(int qty, String processName)
    {
        return Executors.newFixedThreadPool(qty, newThreadFactory(processName));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(String processName)
    {
        return Executors.newSingleThreadScheduledExecutor(newThreadFactory(processName));
    }

    public static ScheduledExecutorService newFixedThreadScheduledPool(int qty, String processName)
    {
        return Executors.newScheduledThreadPool(qty, newThreadFactory(processName));
    }

    public static ThreadFactory newThreadFactory(String processName)
    {
        return newGenericThreadFactory("Curator-" + processName);
    }

    public static ThreadFactory newGenericThreadFactory(String processName)
    {
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler()
        {
            @Override
            public void uncaughtException(Thread t, Throwable e)
            {
  
            }
        };
        return new ThreadFactoryBuilder()
            .setNameFormat(processName + "-%d")
            .setDaemon(true)
            .setUncaughtExceptionHandler(uncaughtExceptionHandler)
            .build();
    }

    public static String getProcessName(Class<?> clazz)
    {
        if ( clazz.isAnonymousClass() )
        {
            return getProcessName(clazz.getEnclosingClass());
        }
        return clazz.getSimpleName();
    }
}