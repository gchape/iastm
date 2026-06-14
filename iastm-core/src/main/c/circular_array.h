typedef struct CircularArray CircularArray;

CircularArray *ca_create(void *initial);
void           ca_destroy(CircularArray *ca);
void           ca_append(CircularArray *ca, void *val, long version);
long           ca_scan(CircularArray *ca, long rv);
void          *ca_scan_value(CircularArray *ca, long slot_idx);
void           ca_expand(CircularArray *ca, float delta);
void           ca_shrink(CircularArray *ca, float delta);