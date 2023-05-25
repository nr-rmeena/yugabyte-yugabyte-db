/*--------------------------------------------------------------------------
 *
 * ybginscan.c
 *	  routines to manage scans of Yugabyte inverted index relations
 *
 * Copyright (c) YugaByte, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * IDENTIFICATION
 *			src/backend/access/ybgin/ybginscan.c
 *--------------------------------------------------------------------------
 */

#include "postgres.h"

#include "access/genam.h"
#include "access/relscan.h"
#include "access/skey.h"
#include "access/ybgin.h"
#include "access/ybgin_private.h"
#include "commands/tablegroup.h"
#include "miscadmin.h"
#include "utils/palloc.h"
#include "utils/memutils.h"
#include "utils/rel.h"

#include "pg_yb_utils.h"
#include "yb/yql/pggate/ybc_pggate.h"

/*
 * Parts copied from ginscan.c ginbeginscan.  Do the same thing except
 * - palloc size of YbginScanOpaqueData
 * - name memory contexts Ybgin
 */
IndexScanDesc
ybginbeginscan(Relation rel, int nkeys, int norderbys)
{
	IndexScanDesc scan;
	GinScanOpaque so;

	/* no order by operators allowed */
	Assert(norderbys == 0);

	scan = RelationGetIndexScan(rel, nkeys, norderbys);

	/* allocate private workspace */
	so = (GinScanOpaque) palloc(sizeof(YbginScanOpaqueData));
	so->keys = NULL;
	so->nkeys = 0;
	so->tempCtx = AllocSetContextCreate(GetCurrentMemoryContext(),
										"Ybgin scan temporary context",
										ALLOCSET_DEFAULT_SIZES);
	so->keyCtx = AllocSetContextCreate(GetCurrentMemoryContext(),
									   "Ybgin scan key context",
									   ALLOCSET_DEFAULT_SIZES);
	initGinState(&so->ginstate, scan->indexRelation);

	scan->opaque = so;

	return scan;
}

void
ybginrescan(IndexScanDesc scan, ScanKey scankey, int nscankeys,
			ScanKey orderbys, int norderbys)
{
	YbginScanOpaque ybso = (YbginScanOpaque) scan->opaque;

	/* Initialize non-yb gin scan opaque fields. */
	ginrescan(scan, scankey, nscankeys, orderbys, norderbys);

	bool is_colocated = YbGetTableProperties(scan->heapRelation)->is_colocated;

	/* Initialize ybgin scan opaque handle. */
	YBCPgPrepareParameters prepare_params = {
		.index_oid = RelationGetRelid(scan->indexRelation),
		.index_only_scan = scan->xs_want_itup,
		.use_secondary_index = true,	/* can't have ybgin primary index */
		.querying_colocated_table = is_colocated,
	};
	HandleYBStatus(YBCPgNewSelect(YBCGetDatabaseOid(scan->heapRelation),
								  YbGetStorageRelid(scan->heapRelation),
								  &prepare_params,
								  YBCIsRegionLocal(scan->heapRelation),
								  &ybso->handle));

	/*
	 * Add any pushdown expression to the main table scan
	 * TODO dedup with YbSetupScanQuals
	 */
	if (scan->yb_rel_pushdown != NULL)
	{
		ListCell   *lc;
		foreach(lc, scan->yb_rel_pushdown->quals)
		{
			Expr *expr = (Expr *) lfirst(lc);
			YBCPgExpr yb_expr = YBCNewEvalExprCall(ybso->handle, expr);
			HandleYBStatus(YbPgDmlAppendQual(ybso->handle,
											 yb_expr,
											 true /* is_primary */));
		}
		foreach(lc, scan->yb_rel_pushdown->colrefs)
		{
			YbExprColrefDesc *param = lfirst_node(YbExprColrefDesc, lc);
			YBCPgTypeAttrs type_attrs = { param->typmod };
			/* Create new PgExpr wrapper for the column reference */
			YBCPgExpr yb_expr = YBCNewColumnRef(ybso->handle,
												param->attno,
												param->typid,
												param->collid,
												&type_attrs);
			/* Add the PgExpr to the statement */
			HandleYBStatus(YbPgDmlAppendColumnRef(ybso->handle,
												  yb_expr,
												  true /* is_primary */));
		}
	}

	/*
	 * Add any pushdown expression to the index relation scan
	 * TODO dedup with YbSetupScanColumnRefs
	 */
	if (scan->yb_idx_pushdown != NULL)
	{
		ListCell   *lc;
		foreach(lc, scan->yb_idx_pushdown->quals)
		{
			Expr *expr = (Expr *) lfirst(lc);
			YBCPgExpr yb_expr = YBCNewEvalExprCall(ybso->handle, expr);
			HandleYBStatus(YbPgDmlAppendQual(ybso->handle,
											 yb_expr,
											 false /* is_primary */));
		}
		foreach(lc, scan->yb_idx_pushdown->colrefs)
		{
			YbExprColrefDesc *param = lfirst_node(YbExprColrefDesc, lc);
			YBCPgTypeAttrs type_attrs = { param->typmod };
			/* Create new PgExpr wrapper for the column reference */
			YBCPgExpr yb_expr = YBCNewColumnRef(ybso->handle,
												param->attno,
												param->typid,
												param->collid,
												&type_attrs);
			/* Add the PgExpr to the statement */
			HandleYBStatus(YbPgDmlAppendColumnRef(ybso->handle,
												  yb_expr,
												  false /* is_primary */));
		}
	}

	/* Initialize ybgin scan opaque is_exec_done. */
	ybso->is_exec_done = false;
}

void
ybginendscan(IndexScanDesc scan)
{
	/*
	 * This frees the scan opaque as if it were a GinScanOpaque rather than a
	 * YbginScanOpaque, but that's fine since the extra field HANDLE doesn't
	 * need special handling.
	 */
	ginendscan(scan);
}
