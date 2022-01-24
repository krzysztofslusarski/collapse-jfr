package pl.ks.profiling.jft.converter.collapsed;

public class ArgumentsParser {
    static Arguments parse(String[] args) {
        Arguments arguments = new Arguments();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-d")) {
                arguments.parserType = ParserType.DIRECTORY;
                arguments.path = args[++i];
            } else if (arg.equals("-f")) {
                arguments.parserType = ParserType.FILE;
                arguments.path = args[++i];
            } else if (arg.equals("-w")) {
                arguments.warmUp = Integer.valueOf(args[++i]);
            } else if (arg.equals("-c")) {
                arguments.coolDown = Integer.valueOf(args[++i]);
            } else if (arg.equals("-ts")) {
                arguments.timestampFeature = TimestampFeature.ENABLED;
            } else if (arg.equals("-al")) {
                arguments.commonLogDateStr = args[++i];
                arguments.durationTimeMsStr = args[++i];
            } else if (arg.equals("-t")) {
                arguments.thread = args[++i];
            }
        }
        return arguments;
    }
}
