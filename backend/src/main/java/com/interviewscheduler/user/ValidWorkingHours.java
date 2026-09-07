package com.interviewscheduler.user;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidWorkingHoursValidator.class)
public @interface ValidWorkingHours {
    String message() default "workingStart and workingEnd must both be set (or both left blank to use the org default), with workingEnd after workingStart";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
