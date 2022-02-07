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

import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCThread;
import org.openjdk.jmc.common.IMCType;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.flightrecorder.CouldNotLoadRecordingException;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.internal.EventArray;
import org.openjdk.jmc.flightrecorder.internal.EventArrays;
import org.openjdk.jmc.flightrecorder.internal.FlightRecordingLoader;

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
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static pl.ks.profiling.jft.converter.collapsed.JfrParser.fetchFlatStackTrace;
import static pl.ks.profiling.jft.converter.collapsed.JfrParser.isAsyncAllocNewTLABEvent;
import static pl.ks.profiling.jft.converter.collapsed.JfrParser.isAsyncAllocOutsideTLABEvent;
import static pl.ks.profiling.jft.converter.collapsed.JfrParser.isAsyncWallEvent;
import static pl.ks.profiling.jft.converter.collapsed.JfrParser.isLockEvent;

public class JftToCollapseStacks {
    private static final Map<String, LongHolder> WALL_MAP = new HashMap<>();
    private static final Map<String, LongHolder> CPU_MAP = new HashMap<>();
    private static final Map<String, LongHolder> ALLOC_COUNT_MAP = new HashMap<>();
    private static final Map<String, LongHolder> ALLOC_SIZE_MAP = new HashMap<>();
    private static final Map<String, LongHolder> MONITOR_MAP = new HashMap<>();
    private static final SimpleDateFormat ACCESS_LOG_FORMAT = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US);
    private static final SimpleDateFormat OUTPUT_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printInfo();
            System.exit(-1);
        }

        Arguments arguments = ArgumentsParser.parse(args);
        StartEndDate startEndDate = calculateDates(arguments, getPaths(arguments));
        String threadLowerCase = arguments.thread == null ? null : arguments.thread.trim().toLowerCase();

        if (arguments.timestampFeature == TimestampFeature.DISABLED) {
            getPaths(arguments).forEach(file1 -> parseFile(file1, startEndDate, threadLowerCase));
            writeToFile();
        } else {
            getPaths(arguments).forEach(JftToCollapseStacks::writeCollapsedWithTimestamp);
        }
    }

    private static Stream<Path> getPaths(Arguments arguments) throws IOException {
        if (arguments.parserType == ParserType.DIRECTORY) {
            return Files.walk(Paths.get(arguments.path))
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase().endsWith(".jfr") || file.getFileName().toString().toLowerCase().endsWith(".jfr.gz"));
        }
        return Stream.of(Paths.get(arguments.path));
    }

    private static StartEndDate calculateDates(Arguments arguments, Stream<Path> paths) throws ParseException, CouldNotLoadRecordingException, IOException {
        if (arguments.commonLogDateStr != null) {
            return calculateDatesFromAccessLog(arguments);
        } else if (arguments.coolDown != 0 || arguments.warmUp != 0) {
            return calculateDatesWithCoolDownAndWarmUp(arguments, paths);
        }
        return null;
    }

    private static StartEndDate calculateDatesWithCoolDownAndWarmUp(Arguments arguments, Stream<Path> paths) throws CouldNotLoadRecordingException, IOException {
        StartEndDate startEndDate = new StartEndDate();
        for (Path path : paths.collect(Collectors.toList())) {
            EventArrays flightRecording = getFlightRecording(path);
            for (EventArray eventArray : flightRecording.getArrays()) {
                if (!isAsyncWallEvent(eventArray) && !isLockEvent(eventArray) && !isAsyncAllocNewTLABEvent(eventArray) && !isAsyncAllocOutsideTLABEvent(eventArray)) {
                    continue;
                }
                IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
                for (IItem event : eventArray.getEvents()) {
                    long startTimestamp = startTimeAccessor.getMember(event).longValue();
                    Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
                    if (startEndDate.endDate == null || startEndDate.endDate.isBefore(eventDate)) {
                        startEndDate.endDate = eventDate;
                    }
                    if (startEndDate.startDate == null || startEndDate.startDate.isAfter(eventDate)) {
                        startEndDate.startDate = eventDate;
                    }
                }
            }
        }

        startEndDate.startDate = startEndDate.startDate.plus(arguments.warmUp, ChronoUnit.SECONDS);
        startEndDate.endDate = startEndDate.endDate.minus(arguments.coolDown, ChronoUnit.SECONDS);

        return startEndDate;
    }

    private static StartEndDate calculateDatesFromAccessLog(Arguments arguments) throws ParseException {
        StartEndDate startEndDate = new StartEndDate();
        startEndDate.endDate = getCommonLogDate(arguments.commonLogDateStr).plus(1, ChronoUnit.SECONDS);
        Long timeMs = Long.parseLong(arguments.durationTimeMsStr);
        startEndDate.startDate = startEndDate.endDate.minus(1, ChronoUnit.SECONDS).minus(timeMs, ChronoUnit.MILLIS);
        return startEndDate;
    }

    private static void writeToFile() throws IOException {
        System.out.println("Saving to collapsed stack files...");
        String saveDir = Paths.get("").toAbsolutePath().toString();
        boolean wallDifferentThenCpu = true;
        if (WALL_MAP.size() == CPU_MAP.size()) {
            Long wallStackCount = WALL_MAP.values().stream()
                    .map(LongHolder::getValue)
                    .reduce(0L, Long::sum);
            Long cpuStackCount = WALL_MAP.values().stream()
                    .map(LongHolder::getValue)
                    .reduce(0L, Long::sum);
            if (Objects.equals(cpuStackCount, wallStackCount)) {
                wallDifferentThenCpu = false;
            }
        }
        if (wallDifferentThenCpu) {
            CollapsedStackWriter.saveFile(saveDir, "wall.collapsed", WALL_MAP);
        } else {
            System.out.println("Omitting wall file, has same frames as CPU");
        }
        CollapsedStackWriter.saveFile(saveDir, "cpu.collapsed", CPU_MAP);
        if (ALLOC_COUNT_MAP.size() > 0) {
            CollapsedStackWriter.saveFile(saveDir, "alloc.count.collapsed", ALLOC_COUNT_MAP);
        }
        if (ALLOC_SIZE_MAP.size() > 0) {
            CollapsedStackWriter.saveFile(saveDir, "alloc.size.collapsed", ALLOC_SIZE_MAP);
        }
        if (MONITOR_MAP.size() > 0) {
            CollapsedStackWriter.saveFile(saveDir, "lock.collapsed", MONITOR_MAP);
        }
        System.out.println("Done");
    }

    private static void printInfo() {
        System.out.println("Options:");
        System.out.println("  -d <arg> - scan the <arg> directory to find .jfr and .jfr.gz files");
        System.out.println("  -f <arg> - parse only the <arg> file");
        System.out.println("  -ts - add timestamps to collapsed stack files");
        System.out.println("  -al <arg1> <arg2> - filter by access log mode, see example below");
        System.out.println("  -t - filter by thread (doesn't work with -ts)");
        System.out.println("  -w - warmup in seconds - how many seconds from the beginning should be omitted");
        System.out.println("  -c - cooldown in seconds - how many seconds from the end should be omitted");
        System.out.println("Proper usage:");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> - will merge all files with .jfr extensions to cpu/wall/lock/alloc collapsed stack files");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> - will convert one file to cpu/wall/lock/alloc collapsed stack files");
        System.out.println("You can add end date in \"Common Log Format\" and duration in ms to filter by time.");
        System.out.println("It is designed to use with access log files. Usage:");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> -al <end date> <duration>");
        System.out.println("  java -jar collapse-jfr-full.jar -d <dir> -al <end date> <duration> -t <thread>");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> -al <end date> <duration>");
        System.out.println("  java -jar collapse-jfr-full.jar -f <file> -al <end date> <duration> -t <thread>");
        System.out.println("Example:");
        System.out.println("  access log entry: [17/Sep/2020:13:03:23 +0200] [POST /app/request HTTP/1.1] [302] [- bytes] [23513 ms] [http-nio-8080-exec-250]");
        System.out.println("  java -jar collapse-jfr-full.jar -d . -al \"17/Sep/2020:13:03:23 +0200\" 23513 -t http-nio-8080-exec-250");
    }

    private static void writeCollapsedWithTimestamp(Path file) {
        System.out.println("Input file: " + file.getFileName());
        System.out.println("Converting JFR to collapsed stack ...");

        String saveDir = Paths.get("").toAbsolutePath().toString();

        try (
                Writer wallOutput = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(saveDir + "/" + "wall.timestamps.collapsed.gz")));
                Writer cpuOutput = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(saveDir + "/" + "cpu.timestamps.collapsed.gz")));
                Writer monitorOutput = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(saveDir + "/" + "lock.timestamps.collapsed.gz")));
                Writer allocCountOutput = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(saveDir + "/" + "alloc.count.timestamps.collapsed.gz")));
                Writer allocSizeOutput = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(saveDir + "/" + "alloc.size.timestamps.collapsed.gz")));
        ) {
            EventArrays flightRecording = getFlightRecording(file);

            for (EventArray eventArray : flightRecording.getArrays()) {
                if (isAsyncWallEvent(eventArray)) {
                    processWallEventWithTimeStamps(wallOutput, cpuOutput, eventArray);
                } else if (isLockEvent(eventArray)) {
                    processLockEventWithTimeStamps(monitorOutput, eventArray);
                } else if (isAsyncAllocNewTLABEvent(eventArray)) {
                    processAllocEventWithTimeStamps(allocCountOutput, allocSizeOutput, eventArray, false);
                } else if (isAsyncAllocOutsideTLABEvent(eventArray)) {
                    processAllocEventWithTimeStamps(allocCountOutput, allocSizeOutput, eventArray, true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void processAllocEventWithTimeStamps(Writer allocCountOutput, Writer allocSizeOutput, EventArray eventArray, boolean outsideTlab) throws IOException {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<IQuantity, IItem> allocationSizeAccessor = JfrParser.findAllocSizeAccessor(eventArray);
        IMemberAccessor<IMCType, IItem> objectClassAccessor = JfrParser.findObjectClassAccessor(eventArray);

        for (IItem event : eventArray.getEvents()) {
            long startTimestamp = startTimeAccessor.getMember(event).longValue();
            Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
            String objectClass = objectClassAccessor.getMember(event).getFullName();
            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor) + ";" + objectClass + (outsideTlab ? "_[i]" : "_[k]");
            long size = allocationSizeAccessor.getMember(event).longValue();
            writeStackTrace(allocCountOutput, eventDate, stacktrace);
            writeStackTrace(allocSizeOutput, eventDate, stacktrace, size);
        }
    }

    private static void processLockEventWithTimeStamps(Writer monitorOutput, EventArray eventArray) throws IOException {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<IMCType, IItem> monitorClassAccessor = JfrParser.findMonitorClassAccessor(eventArray);

        for (IItem event : eventArray.getEvents()) {
            long startTimestamp = startTimeAccessor.getMember(event).longValue();
            Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
            String monitorClass = monitorClassAccessor.getMember(event).getFullName();
            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor) + ";" + monitorClass + "_[i]";
            writeStackTrace(monitorOutput, eventDate, stacktrace);
        }
    }

    private static void processWallEventWithTimeStamps(Writer wallOutput, Writer cpuOutput, EventArray eventArray) throws IOException {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<String, IItem> stateAccessor = JfrParser.findStateAccessor(eventArray);
        for (IItem event : eventArray.getEvents()) {
            long startTimestamp = startTimeAccessor.getMember(event).longValue();
            Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor);
            writeStackTrace(wallOutput, eventDate, stacktrace);
            String state = stateAccessor.getMember(event);
            if (JfrParser.isConsumingCpu(state)) {
                writeStackTrace(cpuOutput, eventDate, stacktrace);
            }
        }
    }

    private static void parseFile(Path file, StartEndDate startEndDate, String thread) {
        System.out.println("Input file: " + file.getFileName());
        System.out.println("Converting JFR to collapsed stack ...");

        try {
            EventArrays flightRecording = getFlightRecording(file);

            for (EventArray eventArray : flightRecording.getArrays()) {
                if (isAsyncWallEvent(eventArray)) {
                    processWallEvent(startEndDate, thread, eventArray);
                } else if (isLockEvent(eventArray)) {
                    processLockEvent(startEndDate, thread, eventArray);
                } else if (isAsyncAllocNewTLABEvent(eventArray)) {
                    processAllocEvent(startEndDate, thread, eventArray, false);
                } else if (isAsyncAllocOutsideTLABEvent(eventArray)) {
                    processAllocEvent(startEndDate, thread, eventArray, true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void processAllocEvent(StartEndDate startEndDate, String thread, EventArray eventArray, boolean outsideTlab) {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<IQuantity, IItem> allocationSizeAccessor = JfrParser.findAllocSizeAccessor(eventArray);
        IMemberAccessor<IMCType, IItem> objectClassAccessor = JfrParser.findObjectClassAccessor(eventArray);

        for (IItem event : eventArray.getEvents()) {
            if (shouldSkipByFilter(startEndDate, thread, startTimeAccessor, threadAccessor, event)) {
                continue;
            }

            String objectClass = objectClassAccessor.getMember(event).getFullName();
            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor) + ";" + objectClass + (outsideTlab ? "_[i]" : "_[k]");
            long size = allocationSizeAccessor.getMember(event).longValue();
            addToAllocMaps(stacktrace, size);
        }
    }

    private static void processLockEvent(StartEndDate startEndDate, String thread, EventArray eventArray) {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<IMCType, IItem> monitorClassAccessor = JfrParser.findMonitorClassAccessor(eventArray);

        for (IItem event : eventArray.getEvents()) {
            if (shouldSkipByFilter(startEndDate, thread, startTimeAccessor, threadAccessor, event)) {
                continue;
            }

            String monitorClass = monitorClassAccessor.getMember(event).getFullName();
            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor) + ";" + monitorClass + "_[i]";
            addToMonitorMap(stacktrace);
        }
    }

    private static void processWallEvent(StartEndDate startEndDate, String thread, EventArray eventArray) {
        IMemberAccessor<IQuantity, IItem> startTimeAccessor = JfrAttributes.START_TIME.getAccessor(eventArray.getType());
        IMemberAccessor<IMCStackTrace, IItem> stackTraceAccessor = JfrAttributes.EVENT_STACKTRACE.getAccessor(eventArray.getType());
        IMemberAccessor<IMCThread, IItem> threadAccessor = JfrAttributes.EVENT_THREAD.getAccessor(eventArray.getType());
        IMemberAccessor<String, IItem> stateAccessor = JfrParser.findStateAccessor(eventArray);

        for (IItem event : eventArray.getEvents()) {
            if (shouldSkipByFilter(startEndDate, thread, startTimeAccessor, threadAccessor, event)) {
                continue;
            }

            String stacktrace = fetchFlatStackTrace(event, stackTraceAccessor, threadAccessor);
            addToWallMap(stacktrace);
            if (stateAccessor != null) {
                addToCpuMapIfConsumingCpu(stateAccessor.getMember(event), stacktrace);
            }
        }
    }

    private static boolean shouldSkipByFilter(StartEndDate startEndDate, String thread, IMemberAccessor<IQuantity, IItem> startTimeAccessor, IMemberAccessor<IMCThread, IItem> threadAccessor, IItem event) {
        if (startEndDate != null) {
            long startTimestamp = startTimeAccessor.getMember(event).longValue();
            Instant eventDate = Instant.ofEpochMilli(startTimestamp / 1000000);
            if (eventDate.isBefore(startEndDate.startDate) || eventDate.isAfter(startEndDate.endDate)) {
                return true;
            }
        }

        if (thread != null) {
            String threadName = threadAccessor.getMember(event).getThreadName().toLowerCase();
            if (!thread.equals(threadName)) {
                return true;
            }
        }
        return false;
    }

    private static void addToCpuMapIfConsumingCpu(String state, String stacktrace) {
        if (JfrParser.isConsumingCpu(state)) {
            addToCpuMap(stacktrace);
        }
    }

    private static void addToCpuMap(String stacktrace) {
        CPU_MAP.computeIfAbsent(stacktrace, stack -> new LongHolder()).increment();
    }

    private static void addToWallMap(String stacktrace) {
        WALL_MAP.computeIfAbsent(stacktrace, stack -> new LongHolder()).increment();
    }

    private static void addToMonitorMap(String stacktrace) {
        MONITOR_MAP.computeIfAbsent(stacktrace, stack -> new LongHolder()).increment();
    }

    private static void addToAllocMaps(String stacktrace, long size) {
        ALLOC_COUNT_MAP.computeIfAbsent(stacktrace, stack -> new LongHolder()).increment();
        ALLOC_SIZE_MAP.computeIfAbsent(stacktrace, stack -> new LongHolder()).addValue(size);
    }

    private static Instant getCommonLogDate(String commonLogDate) throws ParseException {
        Date parse = ACCESS_LOG_FORMAT.parse(commonLogDate);
        return Instant.ofEpochMilli(parse.getTime());
    }

    private static EventArrays getFlightRecording(Path file) throws IOException, CouldNotLoadRecordingException {
        if (file.getFileName().toString().toLowerCase().endsWith(".jfr.gz")) {
            return FlightRecordingLoader.loadStream(new GZIPInputStream(Files.newInputStream(file)), false, false);
        }
        return FlightRecordingLoader.loadStream(Files.newInputStream(file), false, false);
    }

    private static void writeStackTrace(Writer output, Instant eventDate, String stacktrace) throws IOException {
        writeStackTrace(output, eventDate, stacktrace, 1);
    }

    private static void writeStackTrace(Writer output, Instant eventDate, String stacktrace, long count) throws IOException {
        output.write(OUTPUT_FORMAT.format(Date.from(eventDate)));
        output.write(";");
        output.write(stacktrace);
        output.write(" " + count + "\n");
    }
}
