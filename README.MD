# collapse-jfr
This application is a converter from JFR file produced by **Async-profiler** in **wall-clock** mode to collapsed stack files:
* CPU collapsed stack
* Wall-clock collapsed stack
* Lock collapsed stack (Async-profiler 2.0+)
* Alloc count collapsed stack (Async-profiler 2.0+)
* Alloc size collapsed stack (Async-profiler 2.0+)

## Async-profiler version
This tool works with Async-profiler in 1.* and 2.* versions. It works with the Async-profiler in multiple event mode, like `wall,alloc,lock`.

## How to run
`java -jar collapse-jfr-full.jar -d <dir>` - will merge all files with .jfr extensions to cpu/wall collapsed stack files
`java -jar collapse-jfr-full.jar -f <file>` - will convert one file to cpu/wall collapsed stack files

Output files:
* `cpu.collapsed`
* `wall.collapsed`
* `lock.collapsed` - if lock events are present
* `alloc.count.collapsed` - if alloc events are present
* `alloc.size.collapsed` - if alloc events are present

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
