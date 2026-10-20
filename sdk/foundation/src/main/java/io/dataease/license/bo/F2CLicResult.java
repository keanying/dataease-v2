package io.dataease.license.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class F2CLicResult implements Serializable {

    @Serial
    private static final long serialVersionUID = -2187396212648513541L;

    private Status status;

    private F2CLicense license;

    private String message;

    public static F2CLicResult valid(F2CLicense license) {
        F2CLicResult result = new F2CLicResult();
        result.setStatus(Status.valid);
        result.setLicense(license);
        return result;
    }

    public static F2CLicResult invalid(String message) {
        F2CLicResult result = new F2CLicResult();
        result.setStatus(Status.invalid);
        result.setMessage(message);
        return result;
    }

    public static F2CLicResult noRecord() {
        F2CLicResult result = new F2CLicResult();
        result.setStatus(Status.no_record);
        return result;
    }

    public F2CLicResult expired() {
        this.status = Status.expired;
        return this;
    }

    public enum Status {
        valid, expired, no_record, invalid
    }
}
