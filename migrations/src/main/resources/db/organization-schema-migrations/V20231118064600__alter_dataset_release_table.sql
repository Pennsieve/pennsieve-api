alter table dataset_release
    alter column marker drop not null,
    alter column release_date drop not null
;
