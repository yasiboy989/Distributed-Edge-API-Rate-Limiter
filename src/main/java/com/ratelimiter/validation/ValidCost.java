package com.ratelimiter.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A request with cost > limit can never succeed and should be a 400, not a
 * permanent 429 (§6.1). Class-level (not field-level) so the validator can
 * bind the violation to the "cost" property explicitly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CostValidator.class)
public @interface ValidCost {

    String message() default "cost must be less than or equal to limit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
