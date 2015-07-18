package sir.barchable.util;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.text.SimpleDateFormat;

/**
 * @author Sir Barchable
 *         Date: 2/05/15
 */
public class Dates {
    /**
     * Format an interval
     * @param s the interval in seconds
     * @return the interval, formatted "d day(s) hh:mm:ss"
     */
    public static String formatInterval(int s) {
        boolean neg = s < 0;
        s = Math.abs(s);
        int d = s / 86400;
        int h = s % 86400 / 3600;
        int m = s % 3600/ 60;
        s = s % 60;
        StringBuilder buffer = new StringBuilder();
        if (neg) {
            buffer.append('-');
        }
        if (d > 0) {
            buffer.append(d).append(d == 1 ? " day " : " days ");
        }
        buffer.append(String.format("%02d:%02d:%02d", h, m, s));
        return buffer.toString();
    }

    public static String formatIntervalToDayString(int s) {
        GregorianCalendar date = new GregorianCalendar();
        date.add(Calendar.SECOND,s);   

        SimpleDateFormat format = new SimpleDateFormat("MM/dd hh:mm:ss a");
        format.setCalendar(date);
        return format.format(date.getTime());
    }
}
