do $$
declare schema_name text;
begin

	select current_schema() into schema_name;
	--  drop old indexes which covered columns (name,dataset_id,"type") and (name,dataset_id,"type", parent_id)
	DROP INDEX packages_name_dataset_id__parent_id_null_idx;
	DROP INDEX packages_name_dataset_id_parent_id__parent_id_not_null_idx;

	create unique index packages_name_dataset_id__parent_id_null_idx on packages (name,dataset_id) where parent_id is null;
	create unique index packages_name_dataset_id_parent_id__parent_id_not_null_idx on packages (name,dataset_id,parent_id) where parent_id is NOT null;
	
exception when others then
	raise notice 'Error with schema: %', schema_name;
	raise notice '% %', SQLERRM, SQLSTATE;
	rollback;

end; $$