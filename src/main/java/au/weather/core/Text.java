package au.weather.core;

import lombok.experimental.UtilityClass;

/**
 * The two things this system does to a string on its way into a column, written once.
 *
 * <p>Both existed three times over. {@code blankToNull} was identical in three classes;
 * {@code truncate} was spelled three different ways, and one of the three threw a
 * {@code NullPointerException} on a null it was never given by accident (D-149).
 */
@UtilityClass
public class Text {

    /**
     * A blank string is an absent one. An HTML select left on "every region" submits an empty
     * parameter rather than no parameter, and a spreadsheet cell cleared by an operator arrives as
     * whitespace — both mean "not set", and a column holding {@code ""} means something else.
     */
    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Cut to fit its column. Every one of these is a text field an upstream controls the length of,
     * so the choice is between a truncated value and a failed insert — and a truncated remark is
     * still evidence, while a lost row is not.
     */
    public static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
