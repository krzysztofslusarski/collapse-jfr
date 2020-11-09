# collapse-jfr
This application is a converter from JFR file produced by **Async-profiler** in **wall-clock** mode to 2 collapsed stack files:
* CPU collapsed stack
* Wall-clock collapsed stack

## Async-profiler version
This tool works with Async-profiler in 1.* version. I will release new project for 2.*.

## How to compile
Unfortunately this application depends on Oracle JDK 8 jars, which are not in maven repository, so you have to build your version yourself.

* Clone source code
* Download Oracle JDK 8u221 - if you have different update following files may have different version
* Copy following files to oracle dir in cloned project:
  * `jdk1.8.0_221/lib/missioncontrol/plugins/com.jrockit.mc.common_5.5.2.174165.jar`
  * `jdk1.8.0_221/lib/missioncontrol/plugins/com.jrockit.mc.flightrecorder_5.5.2.174165.jar`
* Run `mvn clean package` with `JAVA_HOME` set to JDK 8
* Target jar: `target\collapse-jfr-full.jar`

## How to run
`java -jar collapse-jfr-full.jar -d <dir>` - will merge all files with .jfr extensions to cpu/wall collapsed stack files
`java -jar collapse-jfr-full.jar -f <file>` - will convert one file to cpu/wall collapsed stack files

Output files:
* `cpu.collapsed`
* `wall.collapsed`

will be created in the directory you started process.

## Access log filter
Collapse-jfr can filter your stack with 
* end date
* duration
* thread id

It is design for access log/common log format.

Usage:
```
java -jar collapse-jfr-full.jar -d <dir> <end date> <duration>
java -jar collapse-jfr-full.jar -d <dir> <end date> <duration> <thread>
java -jar collapse-jfr-full.jar -f <file> <end date> <duration>
java -jar collapse-jfr-full.jar -f <file> <end date> <duration> <thread>
``` 
Example:
For access log entry: 
```
[17/Sep/2020:13:03:23 +0200] [POST /app/request HTTP/1.1] [302] [- bytes] [23513 ms] [http-nio-8080-exec-250]
```
You can run:
```
java -jar collapse-jfr-full.jar -d . "17/Sep/2020:13:03:23 +0200" 23513 http-nio-8080-exec-250
```


  
