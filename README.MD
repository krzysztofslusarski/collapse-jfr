# collapse-jfr
This application is a converter from JFR file produced by **Async-profiler** in **wall-clock** mode to 2 collapsed stack files:
* CPU collapsed stack
* Wall-clock collapsed stack

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


  
