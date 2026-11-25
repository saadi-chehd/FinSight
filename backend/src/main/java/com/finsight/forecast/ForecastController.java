package com.finsight.forecast;

import com.finsight.forecast.ForecastModels.SmeForecastResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoint bridging SME historical daily cash flow to the Python forecasting service. */
@RestController
public class ForecastController {
    private final ForecastService forecastService;

    public ForecastController(ForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping("/api/smes/{smeId}/forecast")
    public SmeForecastResponse forecast(
            @PathVariable long smeId,
            @RequestParam LocalDate openingDate,
            @RequestParam String openingAmount,
            @RequestParam String currency,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) String horizons,
            @RequestParam(required = false) String model) {
        return forecastService.forecast(smeId, openingDate, openingAmount, currency, endDate, horizons, model);
    }
}
