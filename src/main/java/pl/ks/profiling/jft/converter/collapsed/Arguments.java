package pl.ks.profiling.jft.converter.collapsed;

public class Arguments {
    String path = null;
    ParserType parserType = null;
    TimestampFeature timestampFeature = TimestampFeature.DISABLED;
    String commonLogDateStr = null;
    String durationTimeMsStr = null;
    String thread = null;

    int warmUp = 0;
    int coolDown = 0;
}
