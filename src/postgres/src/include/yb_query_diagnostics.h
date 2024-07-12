/*-------------------------------------------------------------------------
 *
 * yb_query_diagnostics.c
 *    Utilities for Query Diagnostics/Yugabyte (Postgres layer) integration
 *    that have to be defined on the PostgreSQL side.
 *
 * Copyright (c) YugabyteDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * IDENTIFICATION
 *	  src/backend/utils/misc/yb_query_diagnostics.c
 *
 *-------------------------------------------------------------------------
 */

#ifndef YB_QUERY_DIAGNOSTICS_H
#define YB_QUERY_DIAGNOSTICS_H

#include "postgres.h"

#include "storage/s_lock.h"
#include "utils/timestamp.h"

#define YB_QD_MAX_BIND_VARS_LEN 2048

/* GUC variables */
extern int yb_query_diagnostics_bg_worker_interval_ms;

/*
 * Structure to hold the parameters for query diagnostics.
 */
typedef struct YbQueryDiagnosticsParameters
{
	int64 		query_id;			/* Hash code to identify identical normalized queries */
	int 		diagnostics_interval_sec; /* Indicates the duration for which the bundle will run */
	int 		explain_sample_rate; /* Percentage of queries to be explain’ed */
	bool 		explain_analyze;	/* Whether to run EXPLAIN ANALYZE on the query */
	bool 		explain_dist;		/* Whether to run EXPLAIN (DIST) on the query */
	bool 		explain_debug;		/* Whether to run EXPLAIN (DEBUG) on the query */
	int 		bind_var_query_min_duration_ms; /* Minimum duration for a query to be considered for bundling bind variables */
} YbQueryDiagnosticsParameters;

/*
 * Structure to represent each entry within the hash table.
 */
typedef struct YbQueryDiagnosticsEntry
{
	YbQueryDiagnosticsParameters params; /* parameters for this query-diagnostics entry */
	TimestampTz	start_time;			/* time when the query-diagnostics for this entry started */
	char		path[MAXPGPATH]; 	/* path to the file where bundle data is stored */
	slock_t		mutex;				/* protects following fields only: */
	char		bind_vars[YB_QD_MAX_BIND_VARS_LEN];	/* holds the bind_variables data until flushed to disc */
} YbQueryDiagnosticsEntry;

extern void YbQueryDiagnosticsInstallHook(void);
extern Size YbQueryDiagnosticsShmemSize(void);
extern void YbQueryDiagnosticsShmemInit(void);
extern void YbQueryDiagnosticsBgWorkerRegister(void);
extern void YbQueryDiagnosticsMain(Datum main_arg);

#endif                            /* YB_QUERY_DIAGNOSTICS_H */
