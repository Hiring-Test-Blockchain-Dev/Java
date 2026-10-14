CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  wallet_address TEXT UNIQUE,
  email TEXT UNIQUE,
  password_hash TEXT,
  first_name TEXT,
  last_name TEXT,
  role TEXT NOT NULL DEFAULT 'user',
  is_active INTEGER NOT NULL DEFAULT 1,
  kyc_status TEXT NOT NULL DEFAULT 'unverified',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  institution_name TEXT NOT NULL,
  account_name TEXT,
  account_type TEXT,
  mask TEXT,
  current_balance REAL NOT NULL DEFAULT 0,
  is_active INTEGER NOT NULL DEFAULT 1,
  provider TEXT NOT NULL DEFAULT 'demo',
  external_item_id TEXT,
  link_status TEXT NOT NULL DEFAULT 'linked',
  linked_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS accounts_user_idx ON accounts(user_id);

CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  amount REAL NOT NULL DEFAULT 0,
  date TEXT NOT NULL,
  name TEXT,
  merchant_name TEXT,
  category TEXT,
  subcategory TEXT,
  notes TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS transactions_account_idx ON transactions(account_id);
CREATE INDEX IF NOT EXISTS transactions_date_idx ON transactions(date);
CREATE INDEX IF NOT EXISTS transactions_category_idx ON transactions(category);

CREATE TABLE IF NOT EXISTS budgets (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  category TEXT NOT NULL,
  amount REAL NOT NULL DEFAULT 0,
  period TEXT NOT NULL DEFAULT 'monthly',
  start_date TEXT NOT NULL,
  end_date TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS budgets_user_idx ON budgets(user_id);

CREATE TABLE IF NOT EXISTS savings_goals (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  target_amount REAL NOT NULL,
  current_amount REAL NOT NULL DEFAULT 0,
  target_date TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS savings_user_idx ON savings_goals(user_id);

CREATE TABLE IF NOT EXISTS kyc_submissions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  full_name TEXT NOT NULL,
  date_of_birth TEXT NOT NULL,
  country TEXT NOT NULL,
  document_type TEXT NOT NULL,
  document_last4 TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  review_notes TEXT,
  submitted_at TEXT NOT NULL,
  reviewed_at TEXT
);
CREATE INDEX IF NOT EXISTS kyc_user_idx ON kyc_submissions(user_id);
CREATE INDEX IF NOT EXISTS kyc_status_idx ON kyc_submissions(status);

CREATE TABLE IF NOT EXISTS coach_messages (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS coach_user_idx ON coach_messages(user_id, created_at);

CREATE TABLE IF NOT EXISTS categorization_feedback (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  transaction_id TEXT,
  merchant_name TEXT NOT NULL,
  amount REAL NOT NULL DEFAULT 0,
  description TEXT,
  original_category TEXT,
  user_category TEXT NOT NULL,
  user_subcategory TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS feedback_user_idx ON categorization_feedback(user_id);
CREATE INDEX IF NOT EXISTS feedback_tx_idx ON categorization_feedback(transaction_id);
CREATE INDEX IF NOT EXISTS feedback_merchant_idx ON categorization_feedback(merchant_name);

CREATE TABLE IF NOT EXISTS fraud_signals (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  signal_type TEXT NOT NULL,
  severity TEXT NOT NULL,
  description TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS fraud_user_idx ON fraud_signals(user_id);

CREATE TABLE IF NOT EXISTS loan_eligibility_results (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  risk_score INTEGER NOT NULL,
  decision TEXT NOT NULL,
  reason_codes TEXT NOT NULL,
  factors TEXT NOT NULL,
  recommended_limit REAL,
  model_version TEXT NOT NULL,
  chain_id INTEGER,
  attestation_contract TEXT,
  attestation_id TEXT,
  attestation_tx_hash TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS eligibility_user_idx ON loan_eligibility_results(user_id);

CREATE TABLE IF NOT EXISTS audit_logs (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  action TEXT NOT NULL,
  resource_type TEXT,
  resource_id TEXT,
  details TEXT,
  ip_address TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS audit_user_idx ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS audit_created_idx ON audit_logs(created_at);

CREATE TABLE IF NOT EXISTS loans (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  amount REAL NOT NULL DEFAULT 0,
  interest_rate_pct REAL NOT NULL,
  term_months INTEGER NOT NULL,
  monthly_payment REAL NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  applied_at TEXT NOT NULL,
  approved_at TEXT,
  disbursed_at TEXT,
  chain_id INTEGER,
  contract_address TEXT,
  on_chain_loan_id TEXT,
  create_tx_hash TEXT,
  status_tx_hash TEXT
);
CREATE INDEX IF NOT EXISTS loans_user_idx ON loans(user_id);
