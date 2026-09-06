package com.interviewscheduler.availability;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidAvailabilityWindowValidator
        implements ConstraintValidator<ValidAvailabilityWindow, AvailabilityRequest> {

    @Override
    public boolean isValid(AvailabilityRequest request, ConstraintValidatorContext context) {
        if (request == null || request.startTime() == null || request.endTime() == null) {
            return true; // let @NotNull report the missing field(s)
        }
        return request.endTime().isAfter(request.startTime());
    }
}
