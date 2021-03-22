ALTER TABLE "files"
   ADD COLUMN "uuid" UUID NOT NULL DEFAULT gen_random_uuid(),
   ADD CONSTRAINT unique_uuid UNIQUE (uuid);
