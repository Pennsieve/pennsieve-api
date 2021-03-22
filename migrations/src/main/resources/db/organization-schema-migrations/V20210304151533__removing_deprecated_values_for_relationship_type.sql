UPDATE external_publications
SET relationship_type = 'References'
WHERE relationship_type IN ('Cites','IsCitedBy','Continues','HasMetadata','IsCompiledBy','IsContinuedBy')
