package com.verifi.loan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory loan book for the Java hiring exercise.
 * Create, approve, and disburse work. Partial repayment does not.
 * See the README. Do not change LoanBookTest.
 */
public final class LoanBook {
    public enum Status {
        PENDING, APPROVED, DISBURSED, REPAID, DEFAULTED, REJECTED
    }

    public static final class Loan {
        private final long id;
        private final String borrowerId;
        private final long amountCents;
        private Status status;
        private long remainingCents;

        private Loan(long id, String borrowerId, long amountCents) {
            this.id = id;
            this.borrowerId = borrowerId;
            this.amountCents = amountCents;
            this.status = Status.PENDING;
            this.remainingCents = amountCents;
        }

        public long id() { return id; }
        public String borrowerId() { return borrowerId; }
        public long amountCents() { return amountCents; }
        public Status status() { return status; }
        public long remainingCents() { return remainingCents; }
    }

    public static final class LoanException extends RuntimeException {
        private final String code;

        public LoanException(String code) {
            super(code);
            this.code = code;
        }

        public String code() { return code; }
    }

    private final String platformId;
    private final Map<Long, Loan> loans = new LinkedHashMap<>();
    private long nextId = 1;

    public LoanBook(String platformId) {
        if (platformId == null || platformId.isBlank()) throw new LoanException("UNAUTHORIZED");
        this.platformId = platformId;
    }

    public Loan create(String actorId, String borrowerId, long amountCents) {
        requirePlatform(actorId);
        if (borrowerId == null || borrowerId.isBlank() || amountCents <= 0) throw new LoanException("INVALID_AMOUNT");
        Loan loan = new Loan(nextId++, borrowerId, amountCents);
        loans.put(loan.id, loan);
        return loan;
    }

    public void approve(String actorId, long loanId) {
        requirePlatform(actorId);
        transition(loanId, Status.PENDING, Status.APPROVED);
    }

    public void disburse(String actorId, long loanId) {
        requirePlatform(actorId);
        transition(loanId, Status.APPROVED, Status.DISBURSED);
    }

    /**
     * Bug: an Approved loan can be defaulted. Only Disbursed is allowed.
     * Repaid and every other status must throw INVALID_STATUS.
     */
    public void markDefaulted(String actorId, long loanId) {
        requirePlatform(actorId);
        Loan loan = require(loanId);
        if (loan.status != Status.DISBURSED && loan.status != Status.APPROVED) {
            throw new LoanException("INVALID_STATUS");
        }
        loan.status = Status.DEFAULTED;
    }

    /** Candidate: record a partial repayment. See the README. */
    public void recordPayment(String actorId, long loanId, long amountCents) {
        throw new LoanException("TODO");
    }

    /** Candidate: outstanding cents, or LOAN_NOT_FOUND. */
    public long remainingOf(long loanId) {
        throw new LoanException("TODO");
    }

    public Loan get(long loanId) {
        return require(loanId);
    }

    private void requirePlatform(String actorId) {
        if (!platformId.equals(actorId)) throw new LoanException("UNAUTHORIZED");
    }

    private Loan require(long loanId) {
        Loan loan = loans.get(loanId);
        if (loan == null) throw new LoanException("LOAN_NOT_FOUND");
        return loan;
    }

    private void transition(long loanId, Status expected, Status next) {
        Loan loan = require(loanId);
        if (loan.status != expected) throw new LoanException("INVALID_STATUS");
        loan.status = next;
    }
}
