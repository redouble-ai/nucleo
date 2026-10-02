package works.halcyon.ledger;

import java.time.Year;
import java.util.regex.Pattern;

/**
 * A Halcyon frame number: HBW-<model>-<year>-<sequence>, stamped in Kiel and validated by the
 * dealer portal against the production ledger on every warranty claim.
 */
public final class FrameSerial {
    private static final Pattern SHAPE = Pattern.compile("HBW-(M3|K1|C2)-(\\d{4})-(\\d{5})");
    private final String model;
    private final int year;
    private final int sequence;

    private FrameSerial(String model, int year, int sequence) {
        this.model = model;
        this.year = year;
        this.sequence = sequence;
    }

    public static FrameSerial parse(String text) {
        var m = SHAPE.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("Not a Halcyon frame number: " + text);
        }
        int year = Integer.parseInt(m.group(2));
        if (year < 2019 || year > Year.now().getValue()) {
            throw new IllegalArgumentException("Frame year out of range: " + year);
        }
        return new FrameSerial(m.group(1), year, Integer.parseInt(m.group(3)));
    }

    public String model() {
        return model;
    }

    public int year() {
        return year;
    }

    public int sequence() {
        return sequence;
    }

    @Override
    public String toString() {
        return String.format("HBW-%s-%d-%05d", model, year, sequence);
    }
}
