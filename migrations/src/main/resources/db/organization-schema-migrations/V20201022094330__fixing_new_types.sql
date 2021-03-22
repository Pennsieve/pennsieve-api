update files set file_type='CRAM' where s3_key ilike '%\.cram';

with file_subtype as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'subtype'
   and id IN (select package_id from files where file_type='CRAM' and s3_key ilike '%\.cram')
)
update packages
   set type='Unsupported',
       attributes = jsonb_set(attributes, file_subtype.path, '"Sequence"')
  from file_subtype
 where packages.id IN (select package_id from files where file_type='CRAM' and s3_key ilike '%\.cram')
  and file_subtype.id = packages.id ;


with file_icon as (
  select ('{'||index-1||',value}')::text[] as path, id
   from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'icon'
   and id IN (select package_id from files where file_type='CRAM' and s3_key ilike '%\.cram')
)
update packages
   set attributes = jsonb_set(attributes, file_icon.path, '"Genomics"')
  from file_icon
 where packages.id IN (select package_id from files where file_type='CRAM' and s3_key ilike '%\.cram')
   and file_icon.id = packages.id ;


update files set file_type='JPEG200' where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2';
update files set file_type='LSM' where s3_key ilike '%\.lsm';
update files set file_type='NDPI' where s3_key ilike '%\.ndpi';
update files set file_type='OIB' where s3_key ilike '%\.oib';
update files set file_type='OIF' where s3_key ilike '%\.oif';


with file_subtype as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'subtype'
   and id IN (select package_id from files where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2' or s3_key ilike '%\.lsm' or s3_key ilike '%\.ndpi' or s3_key ilike '%\.oib' or s3_key ilike '%\.oif')
)
update packages
   set type='Slide',
       attributes = jsonb_set(attributes, file_subtype.path, '"Image"')
  from file_subtype
 where packages.id IN (select package_id from files where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2' or s3_key ilike '%\.lsm' or s3_key ilike '%\.ndpi' or s3_key ilike '%\.oib' or s3_key ilike '%\.oif')
 and file_subtype.id = packages.id ;


with file_icon as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'icon'
   and id IN (select package_id from files where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2' or s3_key ilike '%\.lsm' or s3_key ilike '%\.ndpi' or s3_key ilike '%\.oib' or s3_key ilike '%\.oif')
)
update packages
   set attributes = jsonb_set(attributes, file_icon.path, '"Microscope"')
  from file_icon
 where packages.id IN (select package_id from files where s3_key ilike '%\.jpx' or s3_key ilike '%\.jp2' or s3_key ilike '%\.lsm' or s3_key ilike '%\.ndpi' or s3_key ilike '%\.oib' or s3_key ilike '%\.oif')
  and file_icon.id = packages.id ;

update files set file_type='NIFTI' where s3_key ilike '%\.nifti';

with file_subtype as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'subtype'
   and id IN (select package_id from files where file_type='NIFTI' and s3_key ilike '%\.nifti')
)
update packages
   set type='MRI',
       attributes = jsonb_set(attributes, file_subtype.path, '"3D Image"')
  from file_subtype
 where packages.id IN (select package_id from files where file_type='NIFTI' and s3_key ilike '%\.nifti')
  and file_subtype.id = packages.id ;


with file_icon as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'icon'
   and id IN (select package_id from files where file_type='NIFTI' and s3_key ilike '%\.nifti')
)
update packages
   set attributes = jsonb_set(attributes, file_icon.path, '"ClinicalImageBrain"')
  from file_icon
 where packages.id IN (select package_id from files where file_type='NIFTI' and s3_key ilike '%\.nifti')
  and file_icon.id = packages.id ;

update files set file_type='ROI' where s3_key ilike '%\.roi';
update files set file_type='SWC' where s3_key ilike '%\.swc';


with file_subtype as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'subtype'
   and id IN (select package_id from files where s3_key ilike '%\.roi' or s3_key ilike '%\.swc')
)
update packages
   set type='Unsupported',
       attributes = jsonb_set(attributes, file_subtype.path, '"Morphology"')
  from file_subtype
 where packages.id IN (select package_id from files where s3_key ilike '%\.roi' or s3_key ilike '%\.swc')
  and file_subtype.id = packages.id ;


with file_icon as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'icon'
   and id IN (select package_id from files where s3_key ilike '%\.roi' or s3_key ilike '%\.swc')
)
update packages
   set attributes = jsonb_set(attributes, file_icon.path, '"ClinicalImageBrain"')
  from file_icon
 where packages.id IN (select package_id from files where s3_key ilike '%\.roi' or s3_key ilike '%\.swc')
 and file_icon.id = packages.id ;

update files set file_type='Text' where s3_key ilike '%\.rtf';

with file_subtype as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'subtype'
   and id IN (select package_id from files where s3_key ilike '%\.rtf')
)
update packages
   set type='Text',
       attributes = jsonb_set(attributes, file_subtype.path, '"Text"')
  from file_subtype
 where packages.id IN (select package_id from files where s3_key ilike '%\.rtf')
  and file_subtype.id = packages.id ;


with file_icon as (
  select ('{'||index-1||',value}')::text[] as path, id
    from packages, jsonb_array_elements(attributes) with ordinality arr(attribute, index)
 where attribute->>'key' = 'icon'
   and id IN (select package_id from files where s3_key ilike '%\.rtf')
)
update packages
   set attributes = jsonb_set(attributes, file_icon.path, '"Text"')
  from file_icon
 where packages.id IN (select package_id from files where s3_key ilike '%\.rtf')
 and file_icon.id = packages.id ;


