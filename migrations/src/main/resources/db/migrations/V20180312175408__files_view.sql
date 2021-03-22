-- Function to create a view that joins all tables of the same name between all org schemas
-- aka a schema that is a number.
-- taken and modified from: http://clarkdave.net/2015/06/aggregate-queries-across-postgresql-schemas/
CREATE OR REPLACE FUNCTION pennsieve.refresh_union_view(table_name text) RETURNS void AS $$
DECLARE
  schema RECORD;
  result RECORD;
  sql TEXT := '';
BEGIN
  FOR schema IN EXECUTE
    format(
      'SELECT schema_name FROM information_schema.schemata WHERE schema_name ~ %L',
      E'^\\d+'
    )
  LOOP
    sql := sql || format('SELECT %s as organization_id, * FROM %I.%I UNION ALL ', schema.schema_name, schema.schema_name, table_name);
  END LOOP;

  IF sql != '' THEN
    -- all views will be created in the pennsieve schema
    EXECUTE
      format('CREATE OR REPLACE VIEW pennsieve.%I AS ', 'all_' || table_name) || left(sql, -11);
  ELSE
    RAISE EXCEPTION 'No organization schemas found to create union view for table: "%".', table_name;
  END IF;
END
$$ LANGUAGE plpgsql;
