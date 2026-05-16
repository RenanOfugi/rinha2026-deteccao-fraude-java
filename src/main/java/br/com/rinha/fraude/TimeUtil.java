package br.com.rinha.fraude;

final class TimeUtil {
    private TimeUtil() {
    }

    static long parseEpochSecond(byte[] data, int start, int end) {
        int year = digits4(data, start);
        int month = digits2(data, start + 5);
        int day = digits2(data, start + 8);
        int hour = digits2(data, start + 11);
        int minute = digits2(data, start + 14);
        int second = digits2(data, start + 17);
        long epochDay = daysFromCivil(year, month, day);
        return (epochDay * 86_400L) + (hour * 3_600L) + (minute * 60L) + second;
    }

    static int hourOfDay(byte[] data, int start) {
        return digits2(data, start + 11);
    }

    static int dayOfWeekMon0(byte[] data, int start) {
        int year = digits4(data, start);
        int month = digits2(data, start + 5);
        int day = digits2(data, start + 8);
        long epochDay = daysFromCivil(year, month, day);
        return Math.floorMod((int) epochDay + 3, 7);
    }

    private static int digits2(byte[] data, int offset) {
        return ((data[offset] - '0') * 10) + (data[offset + 1] - '0');
    }

    private static int digits4(byte[] data, int offset) {
        return ((data[offset] - '0') * 1000)
            + ((data[offset + 1] - '0') * 100)
            + ((data[offset + 2] - '0') * 10)
            + (data[offset + 3] - '0');
    }

    // Howard Hinnant's civil date conversion adapted to Unix epoch days.
    private static long daysFromCivil(int year, int month, int day) {
        year -= month <= 2 ? 1 : 0;
        int era = Math.floorDiv(year >= 0 ? year : year - 399, 400);
        int yoe = year - (era * 400);
        int doy = ((153 * (month + (month > 2 ? -3 : 9))) + 2) / 5 + day - 1;
        int doe = yoe * 365 + (yoe / 4) - (yoe / 100) + doy;
        return (long) era * 146097L + doe - 719468L;
    }
}
