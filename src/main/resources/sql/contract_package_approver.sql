-- ADDED 2026-11-09: the approver of a package, kept on contract_package (next to assignee_id).
-- Safe to run more than once. Run it in the elcm schema before starting the new ELCM service.

SET @has_column := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'contract_package'
    AND COLUMN_NAME = 'approver');

SET @ddl := IF(@has_column = 0,
  'ALTER TABLE contract_package ADD COLUMN approver VARCHAR(255) NULL AFTER assignee_id',
  'SELECT ''approver column already exists'' AS note');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- OPTIONAL one time catch-up for packages that are waiting for approval right now (newest
-- submission REVIEWED). It takes the person on the open APPROVAL task of the outbox. Packages with
-- no open approval task are left empty. Skip it if you only have test data.
UPDATE contract_package p
  JOIN (SELECT s.package_id
          FROM submission s
          JOIN lkp_submission_status ls ON ls.id = s.status_id
         WHERE ls.code = 'REVIEWED'
           AND s.id IN (SELECT MAX(s2.id) FROM submission s2 GROUP BY s2.package_id)) w
    ON w.package_id = p.id
  JOIN (SELECT o.package_id, o.assignee_login
          FROM ecws_task_outbox o
         WHERE o.event_code = 'APPROVAL' AND o.action = 'CREATE' AND o.task_open = 1
           AND o.id IN (SELECT MAX(o2.id) FROM ecws_task_outbox o2
                         WHERE o2.event_code = 'APPROVAL' AND o2.action = 'CREATE'
                           AND o2.task_open = 1 GROUP BY o2.package_id)) t
    ON t.package_id = p.id
   SET p.approver = t.assignee_login
 WHERE p.approver IS NULL AND t.assignee_login IS NOT NULL;
