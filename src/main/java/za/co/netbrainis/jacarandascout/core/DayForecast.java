package za.co.netbrainis.jacarandascout.core;

import java.time.LocalDate;

public record DayForecast(LocalDate date, double precipMm, double precipProbPct, double gustKmh, double tMaxC) {}
