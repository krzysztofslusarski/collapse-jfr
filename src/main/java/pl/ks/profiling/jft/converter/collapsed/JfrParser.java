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

import java.util.List;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCMethod;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCThread;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.StructContentType;
import org.openjdk.jmc.flightrecorder.internal.EventArray;

class JfrParser {
    static boolean isAsyncWallEvent(EventArray event) {
        if (event.getType() instanceof StructContentType) {
            StructContentType structContentType = (StructContentType) event.getType();
            return structContentType.getIdentifier().equals("jdk.ExecutionSample");
        }
        return false;
    }

    static String fetchFlatStackTrace(IItem event, IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor, IMemberAccessor<IMCThread, IItem> threadAccessor) {
        String threadName = threadAccessor.getMember(event).getThreadName();
        List<? extends IMCFrame> frames = stackTraceAccessor.getMember(event).getFrames();

        StringBuilder builder = new StringBuilder();
        builder.append(threadName).append(";");
        for (int i = frames.size() - 1; i >= 0; i--) {
            IMCFrame frame = frames.get(i);
            IMCMethod method = frame.getMethod();

            if (i != frames.size() - 1) {
                builder.append(";");
            }

            try {
                String packageName = method.getType().getPackage().getName() == null ? "" : method.getType().getPackage().getName().replace(".", "/");
                if (packageName.length() > 0) {
                    builder.append(packageName);
                    builder.append("/");
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
            String className = method.getType().getTypeName().replace(".", "/");
            if (className.length() > 0) {
                builder.append(className);
                builder.append(".");
            }

            builder.append(method.getMethodName());
        }

        return builder.toString();
    }
}
