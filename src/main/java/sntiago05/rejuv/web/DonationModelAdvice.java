package sntiago05.rejuv.web;

import java.util.Objects;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import sntiago05.rejuv.services.DonationService;

@ControllerAdvice
public class DonationModelAdvice {

    private final DonationService donationService;

    public DonationModelAdvice(DonationService donationService) {
        this.donationService = Objects.requireNonNull(donationService, "donationService must not be null");
    }

    @ModelAttribute("donation")
    public DonationService.Donation donation() {
        return donationService.get().orElse(null);
    }
}
