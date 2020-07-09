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

import static pl.ks.profiling.jft.converter.collapsed.JfrParser.fetchFlatStackTrace;
import static pl.ks.profiling.jft.converter.collapsed.JfrParser.validEvent;

import com.jrockit.mc.flightrecorder.FlightRecording;
import com.jrockit.mc.flightrecorder.FlightRecordingLoader;
import com.jrockit.mc.flightrecorder.spi.IEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class JftToCollapseStacks {
    private static final Map<String, IntHolder> WALL_MAP = new HashMap<>();
    private static final Map<String, IntHolder> CPU_MAP = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            printInfo();
            System.exit(-1);
        }

        switch (args[0]) {
            case "-d":
                Files.walk(Paths.get(args[1]))
                        .filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".jfr"))
                        .forEach(JftToCollapseStacks::parseFile);
                break;
            case "-f":
                parseFile(Paths.get(args[1]));
                break;
        }

        System.out.println("Saving to collapsed stack files...");
        String saveDir = Paths.get("").toAbsolutePath().toString();
        CollapsedStackWriter.saveFile(saveDir, "wall.collapsed", WALL_MAP);
        CollapsedStackWriter.saveFile(saveDir, "cpu.collapsed", CPU_MAP);
        System.out.println("Done");
    }

    private static void printInfo() {
        System.out.println("Unrecognized options, proper usage:");
        System.out.println("  java -jar collapse-jfr -d <dir> - will merge all files with .jfr extensions to cpu/wall collapsed stack files");
        System.out.println("  java -jar collapse-jfr -f <file> - will convert one file to cpu/wall collapsed stack files");
    }

    private static void parseFile(Path file) {
        System.out.println("Input file: " + file.getFileName());
        System.out.println("Converting JFR to collapsed stack ...");

        try {
            FlightRecording flightRecording = FlightRecordingLoader.loadFile(file.toFile());

            for (IEvent event : flightRecording.createView()) {
                if (!validEvent(event)) {
                    continue;
                }

                String stacktrace = fetchFlatStackTrace(event);
                addToWallMap(stacktrace);
                addToCpuMapIfConsumingCpu(event, stacktrace);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void addToCpuMapIfConsumingCpu(IEvent event, String stacktrace) {
        if (JfrParser.consumingCpu(event)) {
            addToCpuMap(stacktrace);
        }
    }

    private static void addToCpuMap(String stacktrace) {
        CPU_MAP.computeIfAbsent(stacktrace, stack -> new IntHolder()).increment();
    }

    private static void addToWallMap(String stacktrace) {
        WALL_MAP.computeIfAbsent(stacktrace, stack -> new IntHolder()).increment();
    }
}