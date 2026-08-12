package com.ratelimiter.validation;

import com.ratelimiter.model.dto.RateCheckRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CostValidator implements ConstraintValidator<ValidCost, RateCheckRequest> {

    @Override
    public boolean isValid(RateCheckRequest request, ConstraintValidatorContext context) {
        if (request == null || request.limit() == null || request.cost() == null
                || request.cost() <= request.limit()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("cost")
                .addConstraintViolation();
        return false;
    }
}
