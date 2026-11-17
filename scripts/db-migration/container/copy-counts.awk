# Counts the data rows of every COPY block in `pg_restore --data-only -f -`
# output. Prints one line per table: schema.table|rows
# In COPY text format each row is one line (embedded newlines are escaped)
# and a block ends with a line that is exactly backslash-dot.
/^COPY / { name = $2; n = 0; inblock = 1; next }
inblock && $0 == "\\." { print name "|" n; inblock = 0; next }
inblock { n++ }
