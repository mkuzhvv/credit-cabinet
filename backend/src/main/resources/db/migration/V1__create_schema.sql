CREATE TABLE company (
    id uuid PRIMARY KEY,
    name varchar(300) NOT NULL CHECK (name ~ '[^[:space:]]'),
    legal_form varchar(16) NOT NULL CHECK (legal_form IN ('IP', 'OOO')),
    registration_date date NOT NULL,
    country_code varchar(2) NOT NULL CHECK (country_code = 'RU'),
    has_settlement_account boolean NOT NULL,
    has_regular_inflow_last_3_months boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company (id),
    display_name varchar(200) NOT NULL CHECK (display_name ~ '[^[:space:]]'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_app_user_company_id UNIQUE (company_id, id)
);

CREATE TABLE credit_product (
    code varchar(64) PRIMARY KEY CHECK (code ~ '[^[:space:]]'),
    name varchar(200) NOT NULL CHECK (name ~ '[^[:space:]]'),
    description varchar(3000) NOT NULL CHECK (description ~ '[^[:space:]]'),
    active boolean NOT NULL,
    terms_version varchar(64) NOT NULL CHECK (terms_version ~ '[^[:space:]]'),
    currency varchar(3) NOT NULL CHECK (currency = 'RUB'),
    min_amount numeric(14,2) NOT NULL,
    max_amount numeric(14,2) NOT NULL,
    min_term_months integer NOT NULL,
    max_term_months integer NOT NULL,
    annual_rate_percent numeric(5,2) NOT NULL CHECK (annual_rate_percent BETWEEN 0 AND 999.99),
    min_business_age_months integer NOT NULL CHECK (min_business_age_months >= 0),
    financial_period_months integer NOT NULL CHECK (financial_period_months IN (3, 6, 12)),
    financing_type varchar(40) NOT NULL,
    repayment_type varchar(40) NOT NULL,
    requires_settlement_account boolean NOT NULL,
    requires_regular_inflow boolean NOT NULL,
    max_inflow_share_percent numeric(5,2),
    max_debt_period_days integer,
    min_tranche_term_months integer,
    max_tranche_term_months integer,
    max_net_contract_share_percent numeric(5,2),
    max_days_after_final_payment integer,
    general_conditions varchar(3000) NOT NULL CHECK (general_conditions ~ '[^[:space:]]'),
    disbursement_description varchar(1000) NOT NULL CHECK (disbursement_description ~ '[^[:space:]]'),
    repayment_description varchar(1000) NOT NULL CHECK (repayment_description ~ '[^[:space:]]'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_product_amounts CHECK (
        min_amount > 0 AND max_amount BETWEEN min_amount AND 999999999999.99
    ),
    CONSTRAINT ck_product_terms CHECK (
        min_term_months >= 1 AND max_term_months BETWEEN min_term_months AND 600
    ),
    CONSTRAINT ck_product_mechanics CHECK (
        (financing_type = 'TERM_LOAN' AND repayment_type = 'ANNUITY')
        OR (financing_type = 'OVERDRAFT' AND repayment_type = 'ACCOUNT_SWEEP')
        OR (financing_type = 'REVOLVING_LINE' AND repayment_type = 'EQUAL_PRINCIPAL_PER_TRANCHE')
        OR (financing_type = 'NON_REVOLVING_LINE' AND repayment_type = 'CONTRACT_PROCEEDS')
    ),
    CONSTRAINT ck_product_overdraft CHECK (
        (financing_type = 'OVERDRAFT'
            AND min_term_months = 12 AND max_term_months = 12
            AND requires_settlement_account AND requires_regular_inflow
            AND max_inflow_share_percent IS NOT NULL AND max_inflow_share_percent = 30
            AND max_debt_period_days IS NOT NULL AND max_debt_period_days = 30)
        OR (financing_type <> 'OVERDRAFT'
            AND NOT requires_settlement_account AND NOT requires_regular_inflow
            AND max_inflow_share_percent IS NULL AND max_debt_period_days IS NULL)
    ),
    CONSTRAINT ck_product_revolving_line CHECK (
        (financing_type = 'REVOLVING_LINE'
            AND min_term_months = 24 AND max_term_months = 24
            AND min_tranche_term_months IS NOT NULL AND min_tranche_term_months = 3
            AND max_tranche_term_months IS NOT NULL AND max_tranche_term_months = 6)
        OR (financing_type <> 'REVOLVING_LINE'
            AND min_tranche_term_months IS NULL AND max_tranche_term_months IS NULL)
    ),
    CONSTRAINT ck_product_government_contract CHECK (
        (financing_type = 'NON_REVOLVING_LINE'
            AND min_term_months = 1 AND max_term_months = 12
            AND max_net_contract_share_percent IS NOT NULL AND max_net_contract_share_percent = 70
            AND max_days_after_final_payment IS NOT NULL AND max_days_after_final_payment = 30)
        OR (financing_type <> 'NON_REVOLVING_LINE'
            AND max_net_contract_share_percent IS NULL AND max_days_after_final_payment IS NULL)
    )
);

CREATE TABLE credit_application (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company (id),
    created_by_user_id uuid NOT NULL,
    product_code varchar(64) NOT NULL REFERENCES credit_product (code),
    current_assessment_id uuid,
    status varchar(32) NOT NULL CHECK (
        status IN ('DRAFT', 'CHECKING', 'NEEDS_INFO', 'TECHNICAL_HOLD', 'APPROVED', 'DECLINED')
    ),
    version bigint NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 9007199254740991),
    amount numeric(14,2) CONSTRAINT ck_application_amount CHECK (amount BETWEEN 0 AND 999999999999.99),
    term_months integer CHECK (term_months BETWEEN 1 AND 600),
    average_monthly_inflow numeric(14,2) CHECK (average_monthly_inflow BETWEEN 0 AND 999999999999.99),
    average_monthly_outflow numeric(14,2) CHECK (average_monthly_outflow BETWEEN 0 AND 999999999999.99),
    calculation_date date NOT NULL,
    financing_purpose varchar(1000) CHECK (financing_purpose ~ '[^[:space:]]'),
    project_budget numeric(14,2) CHECK (project_budget BETWEEN 0 AND 999999999999.99),
    tranche_term_months integer CHECK (tranche_term_months BETWEEN 1 AND 600),
    gov_contract_present boolean NOT NULL DEFAULT false,
    gov_contract_number varchar(100) CHECK (gov_contract_number ~ '[^[:space:]]'),
    gov_contract_amount numeric(14,2) CHECK (gov_contract_amount BETWEEN 0 AND 999999999999.99),
    gov_advance_amount numeric(14,2) CHECK (gov_advance_amount BETWEEN 0 AND 999999999999.99),
    gov_execution_end_date date,
    gov_final_payment_date date,
    reporting_period_start date NOT NULL,
    reporting_period_end date NOT NULL,
    last_submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_application_creator FOREIGN KEY (company_id, created_by_user_id) REFERENCES app_user (company_id, id),
    CONSTRAINT ck_application_attempt CHECK (
        (status = 'DRAFT' AND current_assessment_id IS NULL AND last_submitted_at IS NULL)
        OR (status <> 'DRAFT' AND current_assessment_id IS NOT NULL AND last_submitted_at IS NOT NULL)
    ),
    CONSTRAINT ck_application_submitted_form CHECK (
    status IN ('DRAFT', 'NEEDS_INFO')
        OR (amount IS NOT NULL AND amount > 0 AND term_months IS NOT NULL
            AND average_monthly_inflow IS NOT NULL AND average_monthly_outflow IS NOT NULL)
    ),
    CONSTRAINT ck_application_government_contract CHECK (
        gov_contract_present OR (
            gov_contract_number IS NULL AND gov_contract_amount IS NULL AND gov_advance_amount IS NULL
            AND gov_execution_end_date IS NULL AND gov_final_payment_date IS NULL
        )
    ),
    CONSTRAINT ck_application_reporting_period CHECK (reporting_period_start <= reporting_period_end)
);

CREATE TABLE credit_assessment (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES credit_application (id),
    attempt_no integer NOT NULL CHECK (attempt_no > 0),
    previous_assessment_id uuid CHECK (previous_assessment_id <> id),
    processing_status varchar(16) NOT NULL CHECK (processing_status IN ('READY', 'RUNNING', 'FINISHED')),
    outcome varchar(32) CHECK (outcome IN ('APPROVED', 'DECLINED', 'NEEDS_INFO', 'TECHNICAL_HOLD')),
    snapshot_schema_version integer NOT NULL CHECK (snapshot_schema_version > 0),
    input_snapshot jsonb NOT NULL CHECK (jsonb_typeof(input_snapshot) = 'object'),
    credit_history_snapshot jsonb CHECK (
        jsonb_typeof(credit_history_snapshot) = 'object' AND credit_history_snapshot <> '{}'::jsonb
    ),
    policy_version varchar(64) NOT NULL CHECK (policy_version ~ '[^[:space:]]'),
    feature_schema_version varchar(64) NOT NULL CHECK (feature_schema_version ~ '[^[:space:]]'),
    expected_model_version varchar(128) NOT NULL CHECK (expected_model_version ~ '[^[:space:]]'),
    actual_model_version varchar(128) CHECK (actual_model_version ~ '[^[:space:]]'),
    risk_cutoff numeric NOT NULL CHECK (risk_cutoff BETWEEN 0 AND 1),
    risk_score numeric CHECK (risk_score BETWEEN 0 AND 1),
    result_payload jsonb CHECK (jsonb_typeof(result_payload) = 'object'),
    next_run_at timestamptz NOT NULL,
    execution_count integer NOT NULL DEFAULT 0 CHECK (execution_count >= 0),
    lock_token uuid,
    locked_until timestamptz,
    last_error_code varchar(80) CHECK (last_error_code ~ '[^[:space:]]'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamptz,
    CONSTRAINT uq_assessment_application_id UNIQUE (application_id, id),
    CONSTRAINT uq_assessment_attempt_no UNIQUE (application_id, attempt_no),
    CONSTRAINT fk_assessment_previous FOREIGN KEY (application_id, previous_assessment_id)
        REFERENCES credit_assessment (application_id, id),
    CONSTRAINT ck_assessment_result CHECK (
        (processing_status = 'FINISHED' AND outcome IS NOT NULL
            AND result_payload IS NOT NULL AND finished_at IS NOT NULL)
        OR (processing_status IN ('READY', 'RUNNING') AND outcome IS NULL
            AND result_payload IS NULL AND finished_at IS NULL)
    ),
    CONSTRAINT ck_assessment_lock CHECK (
        (processing_status = 'RUNNING' AND lock_token IS NOT NULL AND locked_until IS NOT NULL)
        OR (processing_status <> 'RUNNING' AND lock_token IS NULL AND locked_until IS NULL)
    ),
    CONSTRAINT ck_assessment_risk_context CHECK (
        risk_score IS NULL OR (actual_model_version IS NOT NULL AND credit_history_snapshot IS NOT NULL)
    )
);

-- The application and its attempts refer to each other; both tables must exist first.
ALTER TABLE credit_application ADD CONSTRAINT fk_application_current_assessment
    FOREIGN KEY (id, current_assessment_id) REFERENCES credit_assessment (application_id, id);

CREATE UNIQUE INDEX uq_assessment_active ON credit_assessment (application_id)
    WHERE processing_status IN ('READY', 'RUNNING');

CREATE TABLE credit_clarification (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES credit_application (id),
    source_assessment_id uuid NOT NULL,
    rule_code varchar(80) NOT NULL CHECK (rule_code ~ '[^[:space:]]'),
    field_code varchar(120) NOT NULL CHECK (field_code ~ '[^[:space:]]'),
    message varchar(1000) NOT NULL CHECK (message ~ '[^[:space:]]'),
    observed_value numeric(14,2) NOT NULL CHECK (observed_value BETWEEN 0 AND 999999999999.99),
    reporting_period_start date NOT NULL,
    reporting_period_end date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_type varchar(32) CHECK (response_type IN ('CONFIRM_VALUE', 'VALUE_CORRECTED')),
    response_comment varchar(1000) CHECK (response_comment ~ '[^[:space:]]'),
    answered_value numeric(14,2) CHECK (answered_value BETWEEN 0 AND 999999999999.99),
    answered_by_user_id uuid REFERENCES app_user (id),
    answered_at timestamptz,
    response_assessment_id uuid CHECK (response_assessment_id <> source_assessment_id),
    CONSTRAINT uq_clarification_question UNIQUE (source_assessment_id, rule_code, field_code),
    CONSTRAINT fk_clarification_source FOREIGN KEY (application_id, source_assessment_id)
        REFERENCES credit_assessment (application_id, id),
    CONSTRAINT fk_clarification_response FOREIGN KEY (application_id, response_assessment_id)
        REFERENCES credit_assessment (application_id, id),
    CONSTRAINT ck_clarification_period CHECK (reporting_period_start <= reporting_period_end),
    CONSTRAINT ck_clarification_answer CHECK (
        (response_type IS NULL AND response_comment IS NULL AND answered_value IS NULL
            AND answered_by_user_id IS NULL AND answered_at IS NULL AND response_assessment_id IS NULL)
        OR (response_type IS NOT NULL AND answered_value IS NOT NULL
            AND answered_by_user_id IS NOT NULL AND answered_at IS NOT NULL AND response_assessment_id IS NOT NULL
            AND ((response_type = 'CONFIRM_VALUE' AND observed_value = 0 AND answered_value = 0)
                OR (response_type = 'VALUE_CORRECTED' AND answered_value > 0)))
    )
);

CREATE TABLE application_event (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES credit_application (id),
    application_version bigint NOT NULL CHECK (application_version BETWEEN 0 AND 9007199254740991),
    assessment_id uuid,
    from_status varchar(32) CHECK (
        from_status IN ('DRAFT', 'CHECKING', 'NEEDS_INFO', 'TECHNICAL_HOLD', 'APPROVED', 'DECLINED')
    ),
    to_status varchar(32) NOT NULL CHECK (
        to_status IN ('DRAFT', 'CHECKING', 'NEEDS_INFO', 'TECHNICAL_HOLD', 'APPROVED', 'DECLINED')
    ),
    reason_code varchar(80) CHECK (reason_code ~ '[^[:space:]]'),
    actor_user_id uuid REFERENCES app_user (id),
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_event_application_version UNIQUE (application_id, application_version),
    CONSTRAINT fk_event_assessment FOREIGN KEY (application_id, assessment_id)
        REFERENCES credit_assessment (application_id, id),
    CONSTRAINT ck_event_creation CHECK (
        (from_status IS NULL AND to_status = 'DRAFT' AND assessment_id IS NULL)
        OR (from_status IS NOT NULL AND from_status <> to_status)
    )
);

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    company_id uuid NOT NULL REFERENCES company (id),
    operation varchar(64) NOT NULL CHECK (operation ~ '[^[:space:]]'),
    resource_scope varchar(128) NOT NULL CHECK (resource_scope ~ '[^[:space:]]'),
    idempotency_key uuid NOT NULL,
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    status varchar(32) NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'RETRYABLE_FAILURE')),
    claim_token uuid,
    locked_until timestamptz,
    response_status smallint,
    response_body jsonb CHECK (jsonb_typeof(response_body) = 'object'),
    response_headers jsonb CHECK (jsonb_typeof(response_headers) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz,
    CONSTRAINT uq_idempotency_scope UNIQUE (user_id, company_id, operation, resource_scope, idempotency_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (company_id, user_id) REFERENCES app_user (company_id, id),
    CONSTRAINT ck_idempotency_state CHECK (
        (status = 'IN_PROGRESS' AND claim_token IS NOT NULL AND locked_until IS NOT NULL
            AND response_status IS NULL AND response_body IS NULL AND response_headers IS NULL AND expires_at IS NULL)
        OR (status = 'SUCCEEDED' AND claim_token IS NULL AND locked_until IS NULL
            AND response_status IS NOT NULL AND response_status BETWEEN 200 AND 299
            AND response_body IS NOT NULL AND response_headers IS NOT NULL
            AND expires_at IS NOT NULL AND expires_at >= updated_at + interval '24 hours')
        OR (status = 'RETRYABLE_FAILURE' AND claim_token IS NULL AND locked_until IS NULL
            AND response_status IS NULL AND response_body IS NULL AND response_headers IS NULL)
    )
);

CREATE TABLE assistant_conversation (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    company_id uuid NOT NULL REFERENCES company (id),
    next_sequence bigint NOT NULL DEFAULT 1 CHECK (next_sequence BETWEEN 1 AND 9007199254740991),
    active_generation_token uuid,
    generation_locked_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conversation_user FOREIGN KEY (company_id, user_id) REFERENCES app_user (company_id, id),
    CONSTRAINT ck_conversation_lock CHECK (
        (active_generation_token IS NULL AND generation_locked_until IS NULL)
        OR (active_generation_token IS NOT NULL AND generation_locked_until IS NOT NULL)
    )
);

CREATE TABLE assistant_message (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES assistant_conversation (id),
    sequence bigint NOT NULL CHECK (sequence BETWEEN 1 AND 9007199254740991),
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text varchar(3000) NOT NULL CHECK (text ~ '[^[:space:]]'),
    recommended_product_code varchar(64) REFERENCES credit_product (code),
    reply_to_message_id uuid CHECK (reply_to_message_id <> id),
    generation_metadata jsonb CHECK (jsonb_typeof(generation_metadata) = 'object'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_message_sequence UNIQUE (conversation_id, sequence),
    CONSTRAINT uq_message_conversation_id UNIQUE (conversation_id, id),
    CONSTRAINT uq_message_reply UNIQUE (reply_to_message_id),
    CONSTRAINT fk_message_reply FOREIGN KEY (conversation_id, reply_to_message_id)
        REFERENCES assistant_message (conversation_id, id),
    CONSTRAINT ck_message_role_fields CHECK (
        (role = 'USER' AND char_length(text) <= 2000 AND recommended_product_code IS NULL
            AND reply_to_message_id IS NULL AND generation_metadata IS NULL)
        OR (role = 'ASSISTANT' AND reply_to_message_id IS NOT NULL AND generation_metadata IS NOT NULL)
    )
);

CREATE INDEX ix_application_company_updated ON credit_application (company_id, updated_at DESC, id DESC);
CREATE INDEX ix_assessment_ready ON credit_assessment (next_run_at, id) WHERE processing_status = 'READY';
CREATE INDEX ix_assessment_running_lock ON credit_assessment (locked_until) WHERE processing_status = 'RUNNING';
CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at)
    WHERE status IN ('SUCCEEDED', 'RETRYABLE_FAILURE');
