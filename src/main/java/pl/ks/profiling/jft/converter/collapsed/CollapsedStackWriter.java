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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;

class CollapsedStackWriter {
    static void saveFile(String dir, String fileName, Map<String, IntHolder> stackMap) throws IOException {
        System.out.println("Writing to dir: " + dir +" with file name: " + fileName);
        try (Writer wallOutput = new OutputStreamWriter(new FileOutputStream(dir + "/" + fileName))) {
            for (Map.Entry<String, IntHolder> holderEntry : stackMap.entrySet()) {
                wallOutput.write(holderEntry.getKey());
                wallOutput.write(" ");
                wallOutput.write("" + holderEntry.getValue().getValue());
                wallOutput.write("\n");
            }
        }
    }
}
