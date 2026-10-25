INSERT INTO company (id, name, legal_form, registration_date, country_code,
                     has_settlement_account, has_regular_inflow_last_3_months)
VALUES
    ('97daa92f-127c-4f70-8a0c-0711f13c62ef', 'Демо-пекарня', 'OOO', '2019-08-01', 'RU', true, true),
    ('58dd3ac1-9622-4be8-92cb-4ed38371a32b', 'Демо-мастерская', 'IP', '2020-02-10', 'RU', false, false);

INSERT INTO app_user (id, company_id, display_name)
VALUES
    ('b827db91-df42-4d75-b5e0-8f0a7a512ee1', '97daa92f-127c-4f70-8a0c-0711f13c62ef', 'Демо-представитель'),
    ('3b3b0d3a-7a37-49c3-b650-a721c0b17444', '58dd3ac1-9622-4be8-92cb-4ed38371a32b', 'Представитель демо-мастерской');
