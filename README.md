# Java Hiring Screen

This is a 15-minute Java exercise. There is no smart-contract work.

## Your task

`LoanBook` can create, approve, and disburse a loan. It cannot record a **partial repayment**, and `markDefaulted` accepts an approved loan when it should not.

Finish the implementation in `java/src/main/java/com/verifi/loan/LoanBook.java`.

**Do not edit `LoanBookTest`.**

Run the tests:

```bash
mvn -f java/pom.xml -Dtest=LoanBookTest test
```

The tests should fail initially. Make them pass.

## Implement

### 1. `recordPayment(actorId, loanId, amountCents)`

- Only the platform or the borrower may call it.
- Anyone else throws `LoanException` with code `UNAUTHORIZED`.
- The loan must be `DISBURSED`; otherwise, throw `INVALID_STATUS`.
- `amountCents` must be greater than `0` and less than or equal to the remaining balance; otherwise, throw `INVALID_AMOUNT`.
- Decrease the outstanding balance by the payment amount.
- If the remaining balance reaches `0`, set the status to `REPAID`.
- If the loan does not exist, throw `LOAN_NOT_FOUND`.

### 2. `remainingOf(loanId)`

- Return the outstanding balance in cents.
- If the loan does not exist, throw `LOAN_NOT_FOUND`.

### 3. Fix `markDefaulted`

- Only a `DISBURSED` loan may be marked as defaulted.
- `APPROVED`, `REPAID`, and all other statuses must throw `LoanException` with code `INVALID_STATUS`.

Keep the existing method signatures and `LoanException` codes. Do not add a new status type.

## Submit

Clone the repository and enter the project directory:

```bash
git clone https://github.com/Hiring-Test-Blockchain-Dev/Java.git
cd Java
```

Create your own branch and commit your changes locally. **Do not push.**

```bash
git checkout -b <your-name>/loan-payments
git add java/src/main/java/com/verifi/loan/LoanBook.java
git commit -m "Implement partial loan repayments"
```

Run the tests before committing:

```bash
mvn -f java/pom.xml -Dtest=LoanBookTest test
```

## Run the app (optional)

The Spring Boot API and React UI are supporting demos, not part of the exercise.

On Windows PowerShell:

```powershell
powershell -File java/run.ps1
npm run dev:frontend
```

- API: http://localhost:3001
- App: http://localhost:5173

**Requirements:** JDK 17+ and Maven. If they are not already available on your `PATH`, `java/run.ps1` uses portable versions under `.tools`.
