package com.interviewscheduler.user;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidWorkingHoursValidator implements ConstraintValidator<ValidWorkingHours, UpdateUserRequest> {

    @Override
    public boolean isValid(UpdateUserRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        boolean startSet = request.workingStart() != null;
        boolean endSet = request.workingEnd() != null;
        if (startSet != endSet) {
            return false; // one without the other is meaningless
        }
        if (!startSet) {
            return true; // both blank - use the org default
        }
        return request.workingEnd().isAfter(request.workingStart());
    }
}
