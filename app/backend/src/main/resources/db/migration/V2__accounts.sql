-- Accounts (SRS 3.13). External accounts stay empty until external participants arrive.

CREATE TABLE account (
    account_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    participant_id bigint NOT NULL UNIQUE REFERENCES participant (participant_id)
);

-- The opening cash balance of each business date; clearing writes the next date's row.
CREATE TABLE account_balance (
    account_id    bigint NOT NULL REFERENCES account (account_id),
    business_date int NOT NULL CHECK (business_date BETWEEN 19700101 AND 99991231),
    cash          bigint NOT NULL,      -- units of $0.0001
    PRIMARY KEY (account_id, business_date)
);

-- The opening position of each business date; clearing writes the next date's row.
CREATE TABLE account_position (
    account_id    bigint NOT NULL REFERENCES account (account_id),
    business_date int NOT NULL CHECK (business_date BETWEEN 19700101 AND 99991231),
    symbol        varchar(8) NOT NULL REFERENCES instrument (symbol),
    quantity      bigint NOT NULL,
    PRIMARY KEY (account_id, business_date, symbol)
);

-- The internal participant's position, rolled forward without a cash balance (SRS 3.12).
CREATE TABLE house_position (
    business_date int NOT NULL CHECK (business_date BETWEEN 19700101 AND 99991231),
    symbol        varchar(8) NOT NULL REFERENCES instrument (symbol),
    quantity      bigint NOT NULL,
    PRIMARY KEY (business_date, symbol)
);
