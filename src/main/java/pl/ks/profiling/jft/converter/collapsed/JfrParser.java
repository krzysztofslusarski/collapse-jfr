/*
 * Copyright 2020 Krzysztof Slusarski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.ks.profiling.jft.converter.collapsed;

import com.jrockit.mc.common.IMCFrame;
import com.jrockit.mc.common.IMCMethod;
import com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace;
import com.jrockit.mc.flightrecorder.internal.model.FLRThread;
import com.jrockit.mc.flightrecorder.spi.IEvent;
import java.util.List;

class JfrParser {
    static boolean validEvent(IEvent event) {
        return event.getEventType().getPath().startsWith("vm/prof/execution_sample") && (event.getValue("(stackTrace)") != null);
    }

    static String fetchFlatStackTrace(IEvent event) {
        FLRThread thread = (FLRThread) event.getValue("(thread)");

        FLRStackTrace stackTrace = (FLRStackTrace) event.getValue("(stackTrace)");
        List<? extends IMCFrame> frames = stackTrace.getFrames();

        StringBuilder builder = new StringBuilder();
        if (thread != null && thread.getName() != null) {
            builder.append(thread.getName()).append(";");
        }
        for (int i = frames.size() - 1; i >= 0; i--) {
            IMCFrame frame = frames.get(i);
            IMCMethod method = frame.getMethod();

            if (i != frames.size() - 1) {
                builder.append(";");
            }

            String packageName = method.getPackageName().replace(".", "/");
            if (packageName.length() > 0) {
                builder.append(packageName);
                builder.append("/");
            }

            String className = method.getClassName();
            if (className.length() > 0) {
                builder.append(className);
                builder.append(".");
            }

            builder.append(method.getMethodName());
        }

        return builder.toString();
    }

    static boolean consumingCpu(IEvent event) {
        return "STATE_RUNNABLE".equals(String.valueOf(event.getValue("state")));
    }
}
