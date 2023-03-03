do $$
declare schema_name text;
begin

	select current_schema() into schema_name;
	create unique index packages_name_dataset_id__parent_id_null_idx on packages (name,dataset_id,"type") where parent_id is null;
	create unique index packages_name_dataset_id_parent_id__parent_id_not_null_idx on packages (name,dataset_id,"type",parent_id) where parent_id is NOT null;
	
exception when others then
	raise notice 'Error with schema: %', schema_name;
	raise notice '% %', SQLERRM, SQLSTATE;
	rollback;

end; $$