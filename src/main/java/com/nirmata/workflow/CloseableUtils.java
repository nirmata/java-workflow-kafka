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

import java.io.Closeable;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Closeables;

public class CloseableUtils
{
    private static final Logger log = LoggerFactory.getLogger(CloseableUtils.class);


    public static void closeQuietly(Closeable closeable)
    {
        try
        {
            // Here we've instructed Guava to swallow the IOException
            Closeables.close(closeable, true);
        }
        catch ( IOException e )
        {
            // We instructed Guava to swallow the IOException, so this should
            // never happen. Since it did, log it.
            log.error("IOException should not have been thrown.", e);
        }
    }
}
