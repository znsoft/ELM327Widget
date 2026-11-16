package com.obdwidget;

import java.util.Locale;

/**
 * Names, units and formulas for the standard OBD-II "Mode 01" PIDs.
 *
 * Only used by the diagnostic PID scanner: it turns a raw byte payload into a
 * readable value so the settings log can show what the car actually reports.
 */
final class PidNames {

    private static final int T_NONE = 0;
    private static final int T_RAW = 1;      // A
    private static final int T_M40 = 2;      // A - 40
    private static final int T_RPM = 3;      // ((A*256)+B)/4
    private static final int T_PCT = 4;      // A*100/255
    private static final int T_2B = 5;       // (A*256)+B
    private static final int T_VOLT = 6;     // ((A*256)+B)/1000
    private static final int T_MAF = 7;      // ((A*256)+B)/100
    private static final int T_CAT = 8;      // ((A*256)+B)/10 - 40
    private static final int T_RAILREL = 9;  // ((A*256)+B)*0.079
    private static final int T_RAILG = 10;   // ((A*256)+B)*10
    private static final int T_TIMING = 11;  // A/2 - 64
    private static final int T_TRIM = 12;    // (A-128)*100/128
    private static final int T_SIGN16 = 13;  // signed 2-byte
    private static final int T_TORQUE = 14;  // A - 125
    private static final int T_TORQUEREF = 15;
    private static final int T_FUELRATE = 16; // ((A*256)+B)/20
    private static final int T_AFR = 17;      // (2/65536)*(A*256+B)
    private static final int T_ABLOAD = 18;   // (A*256+B)*100/255
    private static final int T_FUELPRES = 19; // A*3
    private static final int T_X10 = 20;      // A*10
    private static final int T_INJ = 21;      // ((A*256)+B)/128 - 210

    private static final String[] NAMES = new String[256];
    private static final String[] UNITS = new String[256];
    private static final int[] TYPES = new int[256];

    static {
        def(0x01, "Статус мониторов, MIL", "", T_NONE);
        def(0x02, "Код неисправности (стоп-кадр)", "", T_NONE);
        def(0x03, "Статус топливной системы", "", T_NONE);
        def(0x04, "Нагрузка двигателя", "%", T_PCT);
        def(0x05, "Температура антифриза", "°C", T_M40);
        def(0x06, "Краткосрочная коррекция, банк 1", "%", T_TRIM);
        def(0x07, "Долгосрочная коррекция, банк 1", "%", T_TRIM);
        def(0x08, "Краткосрочная коррекция, банк 2", "%", T_TRIM);
        def(0x09, "Долгосрочная коррекция, банк 2", "%", T_TRIM);
        def(0x0A, "Давление топлива", "кПа", T_FUELPRES);
        def(0x0B, "Давление во впуске", "кПа", T_RAW);
        def(0x0C, "Обороты двигателя", "об/мин", T_RPM);
        def(0x0D, "Скорость", "км/ч", T_RAW);
        def(0x0E, "Угол опережения зажигания", "°", T_TIMING);
        def(0x0F, "Температура воздуха на впуске", "°C", T_M40);
        def(0x10, "Массовый расход воздуха MAF", "г/с", T_MAF);
        def(0x11, "Положение дросселя", "%", T_PCT);
        def(0x12, "Статус вторичного воздуха", "", T_NONE);
        def(0x13, "Состав лямбда-зондов", "", T_NONE);
        def(0x14, "Датчик O2 1, банк 1", "", T_NONE);
        def(0x15, "Датчик O2 2, банк 1", "", T_NONE);
        def(0x16, "Датчик O2 3, банк 1", "", T_NONE);
        def(0x17, "Датчик O2 4, банк 1", "", T_NONE);
        def(0x18, "Датчик O2 1, банк 2", "", T_NONE);
        def(0x19, "Датчик O2 2, банк 2", "", T_NONE);
        def(0x1A, "Датчик O2 3, банк 2", "", T_NONE);
        def(0x1B, "Датчик O2 4, банк 2", "", T_NONE);
        def(0x1C, "Стандарт OBD", "", T_NONE);
        def(0x1D, "Датчики O2 (доп.)", "", T_NONE);
        def(0x1E, "Состояние вспом. входа", "", T_NONE);
        def(0x1F, "Время работы с момента запуска", "с", T_2B);
        def(0x21, "Пробег с включённой лампой MIL", "км", T_2B);
        def(0x22, "Давление в топливной рампе (отн.)", "кПа", T_RAILREL);
        def(0x23, "Давление в топливной рампе", "кПа", T_RAILG);
        def(0x24, "Датчик O2 1: лямбда", "", T_NONE);
        def(0x2C, "Заданная рециркуляция EGR", "%", T_PCT);
        def(0x2D, "Отклонение EGR", "%", T_TRIM);
        def(0x2E, "Продувка адсорбера", "%", T_PCT);
        def(0x2F, "Уровень топлива в баке", "%", T_PCT);
        def(0x30, "Циклов прогрева с очистки ошибок", "", T_RAW);
        def(0x31, "Пробег с очистки ошибок", "км", T_2B);
        def(0x32, "Давление паров в баке", "Па", T_SIGN16);
        def(0x33, "Атмосферное давление", "кПа", T_RAW);
        def(0x3C, "Температура катализатора, банк 1", "°C", T_CAT);
        def(0x3D, "Температура катализатора, банк 2", "°C", T_CAT);
        def(0x41, "Статус мониторов текущего цикла", "", T_NONE);
        def(0x42, "Напряжение бортовой сети", "В", T_VOLT);
        def(0x43, "Абсолютная нагрузка", "%", T_ABLOAD);
        def(0x44, "Заданное соотношение воздух/топливо", "", T_AFR);
        def(0x45, "Относительное положение дросселя", "%", T_PCT);
        def(0x46, "Температура за бортом", "°C", T_M40);
        def(0x47, "Положение дросселя B", "%", T_PCT);
        def(0x48, "Положение дросселя C", "%", T_PCT);
        def(0x49, "Положение педали газа D", "%", T_PCT);
        def(0x4A, "Положение педали газа E", "%", T_PCT);
        def(0x4B, "Положение педали газа F", "%", T_PCT);
        def(0x4C, "Заданное положение дросселя", "%", T_PCT);
        def(0x4D, "Время работы с лампой MIL", "мин", T_2B);
        def(0x4E, "Время с очистки ошибок", "мин", T_2B);
        def(0x50, "Максимальный расход воздуха", "г/с", T_X10);
        def(0x51, "Тип топлива", "", T_NONE);
        def(0x52, "Доля этанола", "%", T_PCT);
        def(0x55, "Краткосрочная коррекция, банк 1", "%", T_TRIM);
        def(0x56, "Долгосрочная коррекция, банк 1", "%", T_TRIM);
        def(0x59, "Абсолютное давление в рампе", "кПа", T_RAILG);
        def(0x5A, "Относительное положение педали", "%", T_PCT);
        def(0x5C, "Температура масла двигателя", "°C", T_M40);
        def(0x5D, "Угол впрыска топлива", "°", T_INJ);
        def(0x5E, "Расход топлива", "л/ч", T_FUELRATE);
        def(0x5F, "Экологические требования", "", T_NONE);
        def(0x61, "Требуемый момент", "%", T_TORQUE);
        def(0x62, "Фактический момент", "%", T_TORQUE);
        def(0x63, "Опорный момент", "Н·м", T_TORQUEREF);
        def(0x67, "Температура антифриза (датчик 2)", "°C", T_M40);
        def(0x68, "Температура воздуха на впуске (датчик 2)", "°C", T_M40);
        def(0x6B, "Темп. воздуха после холодильника", "°C", T_M40);
        def(0x6E, "Давление наддува", "кПа", T_RAW);
        def(0x70, "Давление наддува (управление)", "кПа", T_RAW);
        def(0x71, "Давление наддува (привод)", "кПа", T_RAW);
        def(0xA6, "Общий расход топлива", "л", T_2B);
    }

    private PidNames() {
    }

    private static void def(int pid, String name, String unit, int type) {
        NAMES[pid] = name;
        UNITS[pid] = unit;
        TYPES[pid] = type;
    }

    static boolean known(int pid) {
        return NAMES[pid] != null;
    }

    static String name(int pid) {
        return NAMES[pid];
    }

    /** Human readable value, or the raw hex when the PID needs byte-level care. */
    static String describe(int pid, byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "нет ответа";
        }
        double a = raw[0] & 0xFF;
        double b = raw.length > 1 ? (raw[1] & 0xFF) : 0;
        int len = raw.length;
        String u = UNITS[pid] == null ? "" : UNITS[pid];

        switch (pid) {
            case 0x01:
                return "MIL=" + ((raw[0] & 0x80) != 0 ? "да" : "нет")
                        + ", кодов неисправностей=" + (raw[0] & 0x7F);
            case 0x03:
                return "банк1=" + fuelSystem(a) + ", банк2=" + fuelSystem(b);
            case 0x13:
            case 0x1D:
                return "маска 0x" + Diag.hex(raw);
            case 0x1C:
                return obdStandard((int) a);
            case 0x51:
                return fuelType((int) a);
            case 0x32:
                return fmt((double) ((short) (((int) a << 8) | (int) b)), 0, u);
            default:
                break;
        }

        switch (TYPES[pid]) {
            case T_M40:
                return fmt(a - 40, 0, u);
            case T_RPM:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 4, 0, u);
            case T_PCT:
                return fmt(a * 100.0 / 255.0, 1, u);
            case T_2B:
                return len < 2 ? "мало данных" : fmt((a * 256) + b, 0, u);
            case T_VOLT:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 1000.0, 2, u);
            case T_MAF:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 100.0, 2, u);
            case T_CAT:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 10.0 - 40, 1, u);
            case T_RAILREL:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) * 0.079, 1, u);
            case T_RAILG:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) * 10, 0, u);
            case T_TIMING:
                return fmt(a / 2.0 - 64.0, 1, u);
            case T_TRIM:
                return fmt((a - 128) * 100.0 / 128.0, 1, u);
            case T_SIGN16:
                return m16(raw, u, false);
            case T_TORQUE:
                return fmt(a - 125, 0, u);
            case T_TORQUEREF:
                return len < 2 ? "мало данных" : fmt((a * 256) + b, 0, u);
            case T_FUELRATE:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 20.0, 2, u);
            case T_AFR:
                return len < 2 ? "мало данных" : fmt((2.0 / 65536.0) * ((a * 256) + b), 3, u);
            case T_ABLOAD:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) * 100.0 / 255.0, 1, u);
            case T_FUELPRES:
                return fmt(a * 3, 0, u);
            case T_X10:
                return fmt(a * 10, 0, u);
            case T_INJ:
                return len < 2 ? "мало данных" : fmt(((a * 256) + b) / 128.0 - 210.0, 2, u);
            case T_RAW:
                return fmt(a, 0, u);
            default:
                return "0x" + Diag.hex(raw);
        }
    }

    private static String m16(byte[] raw, String unit, boolean signedIsDefault) {
        if (raw.length < 2) {
            return "мало данных";
        }
        int v = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        return fmt(signedIsDefault ? v : v, 0, unit);
    }

    private static String fuelSystem(double v) {
        int code = (int) v;
        switch (code) {
            case 1:
                return "1 (open loop)";
            case 2:
                return "2 (closed loop)";
            case 4:
                return "4 (open loop, нагрузка)";
            case 8:
                return "8 (open loop, сбой)";
            case 16:
                return "16 (closed loop, сбой)";
            default:
                return String.valueOf(code);
        }
    }

    private static String obdStandard(int v) {
        switch (v) {
            case 1:
                return "1: OBD-II (CARB)";
            case 2:
                return "2: OBD (EPA)";
            case 3:
                return "3: OBD-II + OBD";
            case 4:
                return "4: OBD-I";
            case 6:
                return "6: EOBD";
            case 7:
                return "7: EOBD + OBD-II";
            case 10:
                return "10: JOBD";
            case 17:
                return "17: OBD-II (Европа)";
            case 19:
                return "19: EMISSIONS";
            default:
                return String.valueOf(v);
        }
    }

    private static String fuelType(int v) {
        switch (v) {
            case 1:
                return "1: бензин";
            case 2:
                return "2: метанол";
            case 4:
                return "4: дизель";
            case 5:
                return "5: LPG";
            case 6:
                return "6: CNG";
            case 8:
                return "8: электро";
            case 17:
                return "17: этанол E85";
            default:
                return String.valueOf(v);
        }
    }

    private static String fmt(double v, int decimals, String unit) {
        String s = String.format(Locale.US, "%." + decimals + "f", v);
        return unit.length() == 0 ? s : s + " " + unit;
    }
}