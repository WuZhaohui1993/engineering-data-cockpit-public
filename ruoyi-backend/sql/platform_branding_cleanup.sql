-- 清理已有初始化数据中的上游示例品牌。
-- 仅匹配已知的示例记录，可重复执行，不覆盖用户自定义数据。

START TRANSACTION;

UPDATE sys_dept
SET dept_name = '工程数据平台',
    leader = '平台管理员',
    email = 'admin@example.com'
WHERE dept_id = 100
  AND dept_name = '若依科技';

UPDATE sys_dept
SET leader = '平台管理员',
    email = 'admin@example.com'
WHERE dept_id IN (101, 102, 103, 104, 105, 106, 107, 108, 109)
  AND leader = '若依';

UPDATE sys_user
SET nick_name = '平台管理员',
    email = 'admin@example.com'
WHERE user_id = 1
  AND nick_name = '若依';

UPDATE sys_user
SET nick_name = '测试用户',
    email = 'test@example.com'
WHERE user_id = 2
  AND nick_name = '若依';

UPDATE sys_user AS target
LEFT JOIN sys_user AS existing
  ON existing.user_name = 'test'
 AND existing.user_id <> target.user_id
SET target.user_name = 'test'
WHERE target.user_id = 2
  AND target.user_name = 'ry'
  AND existing.user_id IS NULL;

DELETE FROM sys_role_menu
WHERE menu_id = 4;

DELETE FROM sys_menu
WHERE menu_id = 4
  AND (menu_name = '若依官网' OR path LIKE '%ruoyi.vip%');

DELETE FROM sys_notice
WHERE notice_id IN (1, 2, 3)
  AND (notice_title LIKE '%若依%'
       OR notice_content LIKE '%若依%'
       OR notice_content LIKE '%RuoYi%');

UPDATE sys_job
SET invoke_target = REPLACE(
        REPLACE(
            REPLACE(invoke_target, 'ryTask.ryNoParams', 'taskRunner.noParams'),
            'ryTask.ryParams', 'taskRunner.params'),
        'ryTask.ryMultipleParams', 'taskRunner.multipleParams')
WHERE invoke_target LIKE 'ryTask.%';

UPDATE sys_job
SET invoke_target = REPLACE(
        invoke_target,
        CONCAT(CHAR(39), 'ry', CHAR(39)),
        CONCAT(CHAR(39), '参数', CHAR(39)))
WHERE invoke_target LIKE 'taskRunner.%';

UPDATE sys_job_log
SET invoke_target = REPLACE(
        REPLACE(
            REPLACE(invoke_target, 'ryTask.ryNoParams', 'taskRunner.noParams'),
            'ryTask.ryParams', 'taskRunner.params'),
        'ryTask.ryMultipleParams', 'taskRunner.multipleParams')
WHERE invoke_target LIKE 'ryTask.%';

UPDATE sys_job_log
SET invoke_target = REPLACE(
        invoke_target,
        CONCAT(CHAR(39), 'ry', CHAR(39)),
        CONCAT(CHAR(39), '参数', CHAR(39)))
WHERE invoke_target LIKE 'taskRunner.%';

COMMIT;
