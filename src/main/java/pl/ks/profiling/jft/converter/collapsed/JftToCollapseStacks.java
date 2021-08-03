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
import com.jrockit.mc.flightrecorder.internal.model.FLRThread;
import com.jrockit.mc.flightrecorder.spi.IEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class JftToCollapseStacks {
    private static final Map<String, IntHolder> WALL_MAP = new HashMap<>();
    private static final Map<String, IntHolder> CPU_MAP = new HashMap<>();
    private static final SimpleDateFormat ACCESS_LOG_FORMAT = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);
    private static final SimpleDateFormat OUTPUT_FORMAT = new SimpleDateFormat("yyyy.MM.dd:HH:mm:ss", Locale.US);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printInfo();
            System.exit(-1);
        }

        String commonLogDateStr = null;
        String durationTimeMsStr = null;

        String thread = null;
        if (args.length > 4) {
            commonLogDateStr = args[2];
            durationTimeMsStr = args[3];
        }

        if (args.length == 5) {
            thread = args[4];
        }
        Instant endDate = commonLogDateStr == null ? null : getCommonLogDate(commonLogDateStr).plus(1, ChronoUnit.SECONDS);
        Long timeMs = durationTimeMsStr == null ? null : Long.parseLong(durationTimeMsStr);
        Instant startDate = endDate == null || timeMs == null ? null : endDate.minus(1, ChronoUnit.SECONDS).minus(timeMs, ChronoUnit.MILLIS);

        String threadLowerCase = thread == null ? null : thread.trim().toLowerCase();

        switch (args[0]) {
            case "-d":
                Files.walk(Paths.get(args[1]))
                        .filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".jfr"))
                        .forEach(file1 -> parseFile(file1, startDate, endDate, threadLowerCase));
                writeToFile();
                break;
            case "-f":
                parseFile(Paths.get(args[1]), startDate, endDate, threadLowerCase);
                writeToFile();
                break;
            case "-dt":
                Files.walk(Paths.get(args[1]))
                        .filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".jfr"))
                        .forEach(JftToCollapseStacks::writeCollapsedWithTimestamp);
                break;
            case "-ft":
                writeCollapsedWithTimestamp(Paths.get(args[1]));
                break;
        }
    }

    private static void writeToFile() throws IOException {
        System.out.println("Saving to collapsed stack files...");
        String saveDir = Paths.get("").toAbsolutePath().toString();
        CollapsedStackWriter.saveFile(saveDir, "wall.collapsed", WALL_MAP);
        CollapsedStackWriter.saveFile(saveDir, "cpu.collapsed", CPU_MAP);
        System.out.println("Done");
    }

    private static void printInfo() {
        System.out.println("Unrecognized options, proper usage:");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> - will merge all files with .jfr extensions to cpu/wall collapsed stack files");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> - will convert one file to cpu/wall collapsed stack files");
        System.out.println("  java -jar collapse-jfr-full.jar -dt <dir> - will merge all files with .jfr extensions to cpu/wall collapsed stack files (with timestamp)");
        System.out.println("  java -jar collapse-jfr-full.jar -ft <file> - will convert one file to cpu/wall collapsed stack files (with timestamp)");
        System.out.println("You can add end date in \"Common Log Format\" and duration in ms to filter by time.");
        System.out.println("It is designed to use with access log files. Usage:");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> <end date> <duration>");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> <end date> <duration> <thread>");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> <end date> <duration>");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> <end date> <duration> <thread>");
        System.out.println("Example:");
        System.out.println("  access log entry: [17/Sep/2020:13:03:23 +0200] [POST /app/request HTTP/1.1] [302] [- bytes] [23513 ms] [http-nio-8080-exec-250]");
        System.out.println("  java -jar collapse-jfr-full.jar -d . \"17/Sep/2020:13:03:23 +0200\" 23513 http-nio-8080-exec-250");
    }

    private static void writeCollapsedWithTimestamp(Path file) {
        System.out.println("Input file: " + file.getFileName());
        System.out.println("Converting JFR to collapsed stack ...");

        String saveDir = Paths.get("").toAbsolutePath().toString();

        try (
                Writer wallOutput = new OutputStreamWriter(new FileOutputStream(saveDir + "/" + "wall.timestamps.collapsed"));
                Writer cpuOutput = new OutputStreamWriter(new FileOutputStream(saveDir + "/" + "cpu.timestamps.collapsed"));
        ) {
            FlightRecording flightRecording = FlightRecordingLoader.loadFile(file.toFile());

            for (IEvent event : flightRecording.createView()) {
                if (!validEvent(event)) {
                    continue;
                }

                long startTimestamp = event.getStartTimestamp();
                Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
                String stacktrace = fetchFlatStackTrace(event);

                writeStackTrace(wallOutput, eventDate, stacktrace);
                if (JfrParser.consumingCpu(event)) {
                    writeStackTrace(cpuOutput, eventDate, stacktrace);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void writeStackTrace(Writer wallOutput, Instant eventDate, String stacktrace) throws IOException {
        wallOutput.write(OUTPUT_FORMAT.format(Date.from(eventDate)));
        wallOutput.write(";");
        wallOutput.write(stacktrace);
        wallOutput.write(" 1\n");
    }

    private static void parseFile(Path file, Instant startDate, Instant endDate, String thread) {
        System.out.println("Input file: " + file.getFileName());
        System.out.println("Converting JFR to collapsed stack ...");

        try {
            FlightRecording flightRecording = FlightRecordingLoader.loadFile(file.toFile());

            for (IEvent event : flightRecording.createView()) {
                if (!validEvent(event)) {
                    continue;
                }

                if (endDate != null && startDate != null) {
                    long startTimestamp = event.getStartTimestamp();
                    Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
                    if (eventDate.isBefore(startDate) || eventDate.isAfter(endDate)) {
                        continue;
                    }
                }

                if (thread != null) {
                    FLRThread loggedThread = (FLRThread) event.getValue("(thread)");
                    if (loggedThread != null && !thread.equals(loggedThread.getName().toLowerCase())) {
                        continue;
                    }
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

    private static Instant getCommonLogDate(String commonLogDate) throws ParseException {
        Date parse = ACCESS_LOG_FORMAT.parse(commonLogDate);
        return Instant.ofEpochMilli(parse.getTime());
    }
}
