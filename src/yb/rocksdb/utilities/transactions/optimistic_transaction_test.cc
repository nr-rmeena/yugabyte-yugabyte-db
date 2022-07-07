//  Copyright (c) 2011-present, Facebook, Inc.  All rights reserved.
//  This source code is licensed under the BSD-style license found in the
//  LICENSE file in the root directory of this source tree. An additional grant
//  of patent rights can be found in the PATENTS file in the same directory.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#ifndef ROCKSDB_LITE

#include <string>

#include "yb/rocksdb/db.h"
#include "yb/rocksdb/utilities/transaction.h"
#include "yb/rocksdb/utilities/optimistic_transaction_db.h"
#include "yb/rocksdb/util/logging.h"
#include "yb/rocksdb/util/testharness.h"
#include "yb/rocksdb/util/testutil.h"

#include "yb/util/test_util.h"

using std::string;

namespace rocksdb {

class OptimisticTransactionTest : public RocksDBTest {
 public:
  OptimisticTransactionDB* txn_db;
  DB* db;
  string dbname;
  Options options;

  OptimisticTransactionTest() {
    options.create_if_missing = true;
    options.max_write_buffer_number = 2;
    dbname = test::TmpDir() + "/optimistic_transaction_testdb";

    CHECK_OK(DestroyDB(dbname, options));
    Status s = OptimisticTransactionDB::Open(options, dbname, &txn_db);
    assert(s.ok());
    db = txn_db->GetBaseDB();
  }
  ~OptimisticTransactionTest() {
    delete txn_db;
    CHECK_OK(DestroyDB(dbname, options));
  }
};

TEST_F(OptimisticTransactionTest, SuccessTest) {
  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, Slice("foo"), Slice("bar")));
  ASSERT_OK(db->Put(write_options, Slice("foo2"), Slice("bar")));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  ASSERT_OK(txn->GetForUpdate(read_options, "foo", &value));
  ASSERT_EQ(value, "bar");

  ASSERT_OK(txn->Put(Slice("foo"), Slice("bar2")));

  ASSERT_OK(txn->GetForUpdate(read_options, "foo", &value));
  ASSERT_EQ(value, "bar2");

  s = txn->Commit();
  ASSERT_OK(s);

  ASSERT_OK(db->Get(read_options, "foo", &value));
  ASSERT_EQ(value, "bar2");

  delete txn;
}

TEST_F(OptimisticTransactionTest, WriteConflictTest) {
  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "foo", "bar"));
  ASSERT_OK(db->Put(write_options, "foo2", "bar"));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  ASSERT_OK(txn->Put("foo", "bar2"));

  // This Put outside of a transaction will conflict with the previous write
  s = db->Put(write_options, "foo", "barz");
  ASSERT_OK(s);

  s = db->Get(read_options, "foo", &value);
  ASSERT_EQ(value, "barz");
  ASSERT_EQ(1, txn->GetNumKeys());

  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());  // Txn should not commit

  // Verify that transaction did not write anything
  ASSERT_OK(db->Get(read_options, "foo", &value));
  ASSERT_EQ(value, "barz");
  ASSERT_OK(db->Get(read_options, "foo2", &value));
  ASSERT_EQ(value, "bar");

  delete txn;
}

TEST_F(OptimisticTransactionTest, WriteConflictTest2) {
  WriteOptions write_options;
  ReadOptions read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "foo", "bar"));
  ASSERT_OK(db->Put(write_options, "foo2", "bar"));

  txn_options.set_snapshot = true;
  Transaction* txn = txn_db->BeginTransaction(write_options, txn_options);
  ASSERT_TRUE(txn);

  // This Put outside of a transaction will conflict with a later write
  s = db->Put(write_options, "foo", "barz");
  ASSERT_OK(s);

  ASSERT_OK(txn->Put("foo", "bar2"));  // Conflicts with write done after snapshot taken

  s = db->Get(read_options, "foo", &value);
  ASSERT_EQ(value, "barz");

  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());  // Txn should not commit

  // Verify that transaction did not write anything
  ASSERT_OK(db->Get(read_options, "foo", &value));
  ASSERT_EQ(value, "barz");
  ASSERT_OK(db->Get(read_options, "foo2", &value));
  ASSERT_EQ(value, "bar");

  delete txn;
}

TEST_F(OptimisticTransactionTest, ReadConflictTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "foo", "bar"));
  ASSERT_OK(db->Put(write_options, "foo2", "bar"));

  txn_options.set_snapshot = true;
  Transaction* txn = txn_db->BeginTransaction(write_options, txn_options);
  ASSERT_TRUE(txn);

  txn->SetSnapshot();
  snapshot_read_options.snapshot = txn->GetSnapshot();

  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "foo", &value));
  ASSERT_EQ(value, "bar");

  // This Put outside of a transaction will conflict with the previous read
  s = db->Put(write_options, "foo", "barz");
  ASSERT_OK(s);

  s = db->Get(read_options, "foo", &value);
  ASSERT_EQ(value, "barz");

  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());  // Txn should not commit

  // Verify that transaction did not write anything
  ASSERT_OK(txn->GetForUpdate(read_options, "foo", &value));
  ASSERT_EQ(value, "barz");
  ASSERT_OK(txn->GetForUpdate(read_options, "foo2", &value));
  ASSERT_EQ(value, "bar");

  delete txn;
}

TEST_F(OptimisticTransactionTest, TxnOnlyTest) {
  // Test to make sure transactions work when there are no other writes in an
  // empty db.

  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  ASSERT_OK(txn->Put("x", "y"));

  s = txn->Commit();
  ASSERT_OK(s);

  delete txn;
}

TEST_F(OptimisticTransactionTest, FlushTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, Slice("foo"), Slice("bar")));
  ASSERT_OK(db->Put(write_options, Slice("foo2"), Slice("bar")));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  snapshot_read_options.snapshot = txn->GetSnapshot();

  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "foo", &value));
  ASSERT_EQ(value, "bar");

  ASSERT_OK(txn->Put(Slice("foo"), Slice("bar2")));

  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "foo", &value));
  ASSERT_EQ(value, "bar2");

  // Put a random key so we have a memtable to flush
  s = db->Put(write_options, "dummy", "dummy");
  ASSERT_OK(s);

  // force a memtable flush
  FlushOptions flush_ops;
  ASSERT_OK(db->Flush(flush_ops));

  s = txn->Commit();
  // txn should commit since the flushed table is still in MemtableList History
  ASSERT_OK(s);

  ASSERT_OK(db->Get(read_options, "foo", &value));
  ASSERT_EQ(value, "bar2");

  delete txn;
}

TEST_F(OptimisticTransactionTest, FlushTest2) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, Slice("foo"), Slice("bar")));
  ASSERT_OK(db->Put(write_options, Slice("foo2"), Slice("bar")));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  snapshot_read_options.snapshot = txn->GetSnapshot();

  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "foo", &value));
  ASSERT_EQ(value, "bar");

  ASSERT_OK(txn->Put(Slice("foo"), Slice("bar2")));

  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "foo", &value));
  ASSERT_EQ(value, "bar2");

  // Put a random key so we have a MemTable to flush
  s = db->Put(write_options, "dummy", "dummy");
  ASSERT_OK(s);

  // force a memtable flush
  FlushOptions flush_ops;
  ASSERT_OK(db->Flush(flush_ops));

  // Put a random key so we have a MemTable to flush
  s = db->Put(write_options, "dummy", "dummy2");
  ASSERT_OK(s);

  // force a memtable flush
  ASSERT_OK(db->Flush(flush_ops));

  s = db->Put(write_options, "dummy", "dummy3");
  ASSERT_OK(s);

  // force a memtable flush
  // Since our test db has max_write_buffer_number=2, this flush will cause
  // the first memtable to get purged from the MemtableList history.
  ASSERT_OK(db->Flush(flush_ops));

  s = txn->Commit();
  // txn should not commit since MemTableList History is not large enough
  ASSERT_TRUE(s.IsTryAgain());

  ASSERT_OK(db->Get(read_options, "foo", &value));
  ASSERT_EQ(value, "bar");

  delete txn;
}

TEST_F(OptimisticTransactionTest, NoSnapshotTest) {
  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "AAA", "bar"));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  // Modify key after transaction start
  ASSERT_OK(db->Put(write_options, "AAA", "bar1"));

  // Read and write without a snapshot
  ASSERT_OK(txn->GetForUpdate(read_options, "AAA", &value));
  ASSERT_EQ(value, "bar1");
  ASSERT_OK(txn->Put("AAA", "bar2"));

  // Should commit since read/write was done after data changed
  s = txn->Commit();
  ASSERT_OK(s);

  ASSERT_OK(txn->GetForUpdate(read_options, "AAA", &value));
  ASSERT_EQ(value, "bar2");

  delete txn;
}

TEST_F(OptimisticTransactionTest, MultipleSnapshotTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "AAA", "bar"));
  ASSERT_OK(db->Put(write_options, "BBB", "bar"));
  ASSERT_OK(db->Put(write_options, "CCC", "bar"));

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  ASSERT_OK(db->Put(write_options, "AAA", "bar1"));

  // Read and write without a snapshot
  ASSERT_OK(txn->GetForUpdate(read_options, "AAA", &value));
  ASSERT_EQ(value, "bar1");
  ASSERT_OK(txn->Put("AAA", "bar2"));

  // Modify BBB before snapshot is taken
  ASSERT_OK(db->Put(write_options, "BBB", "bar1"));

  txn->SetSnapshot();
  snapshot_read_options.snapshot = txn->GetSnapshot();

  // Read and write with snapshot
  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "BBB", &value));
  ASSERT_EQ(value, "bar1");
  ASSERT_OK(txn->Put("BBB", "bar2"));

  ASSERT_OK(db->Put(write_options, "CCC", "bar1"));

  // Set a new snapshot
  txn->SetSnapshot();
  snapshot_read_options.snapshot = txn->GetSnapshot();

  // Read and write with snapshot
  ASSERT_OK(txn->GetForUpdate(snapshot_read_options, "CCC", &value));
  ASSERT_EQ(value, "bar1");
  ASSERT_OK(txn->Put("CCC", "bar2"));

  s = txn->GetForUpdate(read_options, "AAA", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");
  s = txn->GetForUpdate(read_options, "BBB", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");
  s = txn->GetForUpdate(read_options, "CCC", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");

  s = db->Get(read_options, "AAA", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar1");
  s = db->Get(read_options, "BBB", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar1");
  s = db->Get(read_options, "CCC", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar1");

  s = txn->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "AAA", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");
  s = db->Get(read_options, "BBB", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");
  s = db->Get(read_options, "CCC", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "bar2");

  // verify that we track multiple writes to the same key at different snapshots
  delete txn;
  txn = txn_db->BeginTransaction(write_options);

  // Potentially conflicting writes
  ASSERT_OK(db->Put(write_options, "ZZZ", "zzz"));
  ASSERT_OK(db->Put(write_options, "XXX", "xxx"));

  txn->SetSnapshot();

  OptimisticTransactionOptions txn_options;
  txn_options.set_snapshot = true;
  Transaction* txn2 = txn_db->BeginTransaction(write_options, txn_options);
  txn2->SetSnapshot();

  // This should not conflict in txn since the snapshot is later than the
  // previous write (spoiler alert:  it will later conflict with txn2).
  ASSERT_OK(txn->Put("ZZZ", "zzzz"));
  s = txn->Commit();
  ASSERT_OK(s);

  delete txn;

  // This will conflict since the snapshot is earlier than another write to ZZZ
  ASSERT_OK(txn2->Put("ZZZ", "xxxxx"));

  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn2;
}

TEST_F(OptimisticTransactionTest, ColumnFamiliesTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  ColumnFamilyHandle *cfa, *cfb;
  ColumnFamilyOptions cf_options;

  // Create 2 new column families
  s = db->CreateColumnFamily(cf_options, "CFA", &cfa);
  ASSERT_OK(s);
  s = db->CreateColumnFamily(cf_options, "CFB", &cfb);
  ASSERT_OK(s);

  delete cfa;
  delete cfb;
  delete txn_db;

  // open DB with three column families
  std::vector<ColumnFamilyDescriptor> column_families;
  // have to open default column family
  column_families.push_back(
      ColumnFamilyDescriptor(kDefaultColumnFamilyName, ColumnFamilyOptions()));
  // open the new column families
  column_families.push_back(
      ColumnFamilyDescriptor("CFA", ColumnFamilyOptions()));
  column_families.push_back(
      ColumnFamilyDescriptor("CFB", ColumnFamilyOptions()));
  std::vector<ColumnFamilyHandle*> handles;
  s = OptimisticTransactionDB::Open(options, dbname, column_families, &handles,
                                    &txn_db);
  ASSERT_OK(s);
  db = txn_db->GetBaseDB();

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  txn->SetSnapshot();
  snapshot_read_options.snapshot = txn->GetSnapshot();

  txn_options.set_snapshot = true;
  Transaction* txn2 = txn_db->BeginTransaction(write_options, txn_options);
  ASSERT_TRUE(txn2);

  // Write some data to the db
  WriteBatch batch;
  batch.Put("foo", "foo");
  batch.Put(handles[1], "AAA", "bar");
  batch.Put(handles[1], "AAAZZZ", "bar");
  s = db->Write(write_options, &batch);
  ASSERT_OK(s);
  ASSERT_OK(db->Delete(write_options, handles[1], "AAAZZZ"));

  // These keys do no conflict with existing writes since they're in
  // different column families
  ASSERT_OK(txn->Delete("AAA"));
  ASSERT_NOK(txn->GetForUpdate(snapshot_read_options, handles[1], "foo", &value));
  Slice key_slice("AAAZZZ");
  Slice value_slices[2] = {Slice("bar"), Slice("bar")};
  ASSERT_OK(txn->Put(handles[2], SliceParts(&key_slice, 1), SliceParts(value_slices, 2)));

  ASSERT_EQ(3, txn->GetNumKeys());

  // Txn should commit
  s = txn->Commit();
  ASSERT_OK(s);
  s = db->Get(read_options, "AAA", &value);
  ASSERT_TRUE(s.IsNotFound());
  s = db->Get(read_options, handles[2], "AAAZZZ", &value);
  ASSERT_EQ(value, "barbar");

  Slice key_slices[3] = {Slice("AAA"), Slice("ZZ"), Slice("Z")};
  Slice value_slice("barbarbar");
  // This write will cause a conflict with the earlier batch write
  ASSERT_OK(txn2->Put(handles[1], SliceParts(key_slices, 3), SliceParts(&value_slice, 1)));

  ASSERT_OK(txn2->Delete(handles[2], "XXX"));
  ASSERT_OK(txn2->Delete(handles[1], "XXX"));
  s = txn2->GetForUpdate(snapshot_read_options, handles[1], "AAA", &value);
  ASSERT_TRUE(s.IsNotFound());

  // Verify txn did not commit
  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());
  s = db->Get(read_options, handles[1], "AAAZZZ", &value);
  ASSERT_EQ(value, "barbar");

  delete txn;
  delete txn2;

  txn = txn_db->BeginTransaction(write_options, txn_options);
  snapshot_read_options.snapshot = txn->GetSnapshot();

  txn2 = txn_db->BeginTransaction(write_options, txn_options);
  ASSERT_TRUE(txn);

  std::vector<ColumnFamilyHandle*> multiget_cfh = {handles[1], handles[2],
                                                   handles[0], handles[2]};
  std::vector<Slice> multiget_keys = {"AAA", "AAAZZZ", "foo", "foo"};
  std::vector<std::string> values(4);

  std::vector<Status> results = txn->MultiGetForUpdate(
      snapshot_read_options, multiget_cfh, multiget_keys, &values);
  ASSERT_OK(results[0]);
  ASSERT_OK(results[1]);
  ASSERT_OK(results[2]);
  ASSERT_TRUE(results[3].IsNotFound());
  ASSERT_EQ(values[0], "bar");
  ASSERT_EQ(values[1], "barbar");
  ASSERT_EQ(values[2], "foo");

  ASSERT_OK(txn->Delete(handles[2], "ZZZ"));
  ASSERT_OK(txn->Put(handles[2], "ZZZ", "YYY"));
  ASSERT_OK(txn->Put(handles[2], "ZZZ", "YYYY"));
  ASSERT_OK(txn->Delete(handles[2], "ZZZ"));
  ASSERT_OK(txn->Put(handles[2], "AAAZZZ", "barbarbar"));

  ASSERT_EQ(5, txn->GetNumKeys());

  // Txn should commit
  s = txn->Commit();
  ASSERT_OK(s);
  s = db->Get(read_options, handles[2], "ZZZ", &value);
  ASSERT_TRUE(s.IsNotFound());

  // Put a key which will conflict with the next txn using the previous snapshot
  ASSERT_OK(db->Put(write_options, handles[2], "foo", "000"));

  results = txn2->MultiGetForUpdate(snapshot_read_options, multiget_cfh,
                                    multiget_keys, &values);
  ASSERT_OK(results[0]);
  ASSERT_OK(results[1]);
  ASSERT_OK(results[2]);
  ASSERT_TRUE(results[3].IsNotFound());
  ASSERT_EQ(values[0], "bar");
  ASSERT_EQ(values[1], "barbar");
  ASSERT_EQ(values[2], "foo");

  // Verify Txn Did not Commit
  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  s = db->DropColumnFamily(handles[1]);
  ASSERT_OK(s);
  s = db->DropColumnFamily(handles[2]);
  ASSERT_OK(s);

  delete txn;
  delete txn2;

  for (auto handle : handles) {
    delete handle;
  }
}

TEST_F(OptimisticTransactionTest, EmptyTest) {
  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  s = db->Put(write_options, "aaa", "aaa");
  ASSERT_OK(s);

  Transaction* txn = txn_db->BeginTransaction(write_options);
  s = txn->Commit();
  ASSERT_OK(s);
  delete txn;

  txn = txn_db->BeginTransaction(write_options);
  txn->Rollback();
  delete txn;

  txn = txn_db->BeginTransaction(write_options);
  s = txn->GetForUpdate(read_options, "aaa", &value);
  ASSERT_EQ(value, "aaa");

  s = txn->Commit();
  ASSERT_OK(s);
  delete txn;

  txn = txn_db->BeginTransaction(write_options);
  txn->SetSnapshot();
  s = txn->GetForUpdate(read_options, "aaa", &value);
  ASSERT_EQ(value, "aaa");

  s = db->Put(write_options, "aaa", "xxx");
  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());
  delete txn;
}

TEST_F(OptimisticTransactionTest, PredicateManyPreceders) {
  WriteOptions write_options;
  ReadOptions read_options1, read_options2;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  txn_options.set_snapshot = true;
  Transaction* txn1 = txn_db->BeginTransaction(write_options, txn_options);
  read_options1.snapshot = txn1->GetSnapshot();

  Transaction* txn2 = txn_db->BeginTransaction(write_options);
  txn2->SetSnapshot();
  read_options2.snapshot = txn2->GetSnapshot();

  std::vector<Slice> multiget_keys = {"1", "2", "3"};
  std::vector<std::string> multiget_values;

  std::vector<Status> results =
      txn1->MultiGetForUpdate(read_options1, multiget_keys, &multiget_values);
  ASSERT_TRUE(results[1].IsNotFound());

  ASSERT_OK(txn2->Put("2", "x"));

  s = txn2->Commit();
  ASSERT_OK(s);

  multiget_values.clear();
  results =
      txn1->MultiGetForUpdate(read_options1, multiget_keys, &multiget_values);
  ASSERT_TRUE(results[1].IsNotFound());

  // should not commit since txn2 wrote a key txn has read
  s = txn1->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn1;
  delete txn2;

  txn1 = txn_db->BeginTransaction(write_options, txn_options);
  read_options1.snapshot = txn1->GetSnapshot();

  txn2 = txn_db->BeginTransaction(write_options, txn_options);
  read_options2.snapshot = txn2->GetSnapshot();

  ASSERT_OK(txn1->Put("4", "x"));

  ASSERT_OK(txn2->Delete("4"));

  // txn1 can commit since txn2's delete hasn't happened yet (it's just batched)
  s = txn1->Commit();
  ASSERT_OK(s);

  s = txn2->GetForUpdate(read_options2, "4", &value);
  ASSERT_TRUE(s.IsNotFound());

  // txn2 cannot commit since txn1 changed "4"
  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn1;
  delete txn2;
}

TEST_F(OptimisticTransactionTest, LostUpdate) {
  WriteOptions write_options;
  ReadOptions read_options, read_options1, read_options2;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  // Test 2 transactions writing to the same key in multiple orders and
  // with/without snapshots

  Transaction* txn1 = txn_db->BeginTransaction(write_options);
  Transaction* txn2 = txn_db->BeginTransaction(write_options);

  ASSERT_OK(txn1->Put("1", "1"));
  ASSERT_OK(txn2->Put("1", "2"));

  s = txn1->Commit();
  ASSERT_OK(s);

  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn1;
  delete txn2;

  txn_options.set_snapshot = true;
  txn1 = txn_db->BeginTransaction(write_options, txn_options);
  read_options1.snapshot = txn1->GetSnapshot();

  txn2 = txn_db->BeginTransaction(write_options, txn_options);
  read_options2.snapshot = txn2->GetSnapshot();

  ASSERT_OK(txn1->Put("1", "3"));
  ASSERT_OK(txn2->Put("1", "4"));

  s = txn1->Commit();
  ASSERT_OK(s);

  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn1;
  delete txn2;

  txn1 = txn_db->BeginTransaction(write_options, txn_options);
  read_options1.snapshot = txn1->GetSnapshot();

  txn2 = txn_db->BeginTransaction(write_options, txn_options);
  read_options2.snapshot = txn2->GetSnapshot();

  ASSERT_OK(txn1->Put("1", "5"));
  s = txn1->Commit();
  ASSERT_OK(s);

  ASSERT_OK(txn2->Put("1", "6"));
  s = txn2->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete txn1;
  delete txn2;

  txn1 = txn_db->BeginTransaction(write_options, txn_options);
  read_options1.snapshot = txn1->GetSnapshot();

  txn2 = txn_db->BeginTransaction(write_options, txn_options);
  read_options2.snapshot = txn2->GetSnapshot();

  ASSERT_OK(txn1->Put("1", "5"));
  s = txn1->Commit();
  ASSERT_OK(s);

  txn2->SetSnapshot();
  ASSERT_OK(txn2->Put("1", "6"));
  s = txn2->Commit();
  ASSERT_OK(s);

  delete txn1;
  delete txn2;

  txn1 = txn_db->BeginTransaction(write_options);
  txn2 = txn_db->BeginTransaction(write_options);

  ASSERT_OK(txn1->Put("1", "7"));
  s = txn1->Commit();
  ASSERT_OK(s);

  ASSERT_OK(txn2->Put("1", "8"));
  s = txn2->Commit();
  ASSERT_OK(s);

  delete txn1;
  delete txn2;

  s = db->Get(read_options, "1", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "8");
}

TEST_F(OptimisticTransactionTest, UntrackedWrites) {
  WriteOptions write_options;
  ReadOptions read_options;
  string value;
  Status s;

  // Verify transaction rollback works for untracked keys.
  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn->PutUntracked("untracked", "0"));
  txn->Rollback();
  s = db->Get(read_options, "untracked", &value);
  ASSERT_TRUE(s.IsNotFound());

  delete txn;
  txn = txn_db->BeginTransaction(write_options);

  ASSERT_OK(txn->Put("tracked", "1"));
  ASSERT_OK(txn->PutUntracked("untracked", "1"));
  ASSERT_OK(txn->MergeUntracked("untracked", "2"));
  ASSERT_OK(txn->DeleteUntracked("untracked"));

  // Write to the untracked key outside of the transaction and verify
  // it doesn't prevent the transaction from committing.
  s = db->Put(write_options, "untracked", "x");
  ASSERT_OK(s);

  s = txn->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "untracked", &value);
  ASSERT_TRUE(s.IsNotFound());

  delete txn;
  txn = txn_db->BeginTransaction(write_options);

  ASSERT_OK(txn->Put("tracked", "10"));
  ASSERT_OK(txn->PutUntracked("untracked", "A"));

  // Write to tracked key outside of the transaction and verify that the
  // untracked keys are not written when the commit fails.
  s = db->Delete(write_options, "tracked");

  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());

  s = db->Get(read_options, "untracked", &value);
  ASSERT_TRUE(s.IsNotFound());

  delete txn;
}

TEST_F(OptimisticTransactionTest, IteratorTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  // Write some keys to the db
  s = db->Put(write_options, "A", "a");
  ASSERT_OK(s);

  s = db->Put(write_options, "G", "g");
  ASSERT_OK(s);

  s = db->Put(write_options, "F", "f");
  ASSERT_OK(s);

  s = db->Put(write_options, "C", "c");
  ASSERT_OK(s);

  s = db->Put(write_options, "D", "d");
  ASSERT_OK(s);

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  // Write some keys in a txn
  s = txn->Put("B", "b");
  ASSERT_OK(s);

  s = txn->Put("H", "h");
  ASSERT_OK(s);

  s = txn->Delete("D");
  ASSERT_OK(s);

  s = txn->Put("E", "e");
  ASSERT_OK(s);

  txn->SetSnapshot();
  const Snapshot* snapshot = txn->GetSnapshot();

  // Write some keys to the db after the snapshot
  s = db->Put(write_options, "BB", "xx");
  ASSERT_OK(s);

  s = db->Put(write_options, "C", "xx");
  ASSERT_OK(s);

  read_options.snapshot = snapshot;
  Iterator* iter = txn->GetIterator(read_options);
  ASSERT_OK(iter->status());
  iter->SeekToFirst();

  // Read all keys via iter and lock them all
  std::string results[] = {"a", "b", "c", "e", "f", "g", "h"};
  for (int i = 0; i < 7; i++) {
    ASSERT_OK(iter->status());
    ASSERT_TRUE(iter->Valid());
    ASSERT_EQ(results[i], iter->value().ToString());

    s = txn->GetForUpdate(read_options, iter->key(), nullptr);
    ASSERT_OK(s);

    iter->Next();
  }
  ASSERT_FALSE(iter->Valid());

  iter->Seek("G");
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("g", iter->value().ToString());

  iter->Prev();
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("f", iter->value().ToString());

  iter->Seek("D");
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("e", iter->value().ToString());

  iter->Seek("C");
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("c", iter->value().ToString());

  iter->Next();
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("e", iter->value().ToString());

  iter->Seek("");
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("a", iter->value().ToString());

  iter->Seek("X");
  ASSERT_OK(iter->status());
  ASSERT_FALSE(iter->Valid());

  iter->SeekToLast();
  ASSERT_OK(iter->status());
  ASSERT_TRUE(iter->Valid());
  ASSERT_EQ("h", iter->value().ToString());

  // key "C" was modified in the db after txn's snapshot.  txn will not commit.
  s = txn->Commit();
  ASSERT_TRUE(s.IsBusy());

  delete iter;
  delete txn;
}

TEST_F(OptimisticTransactionTest, SavepointTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  Transaction* txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  s = txn->RollbackToSavePoint();
  ASSERT_TRUE(s.IsNotFound());

  txn->SetSavePoint();  // 1

  ASSERT_OK(txn->RollbackToSavePoint());  // Rollback to beginning of txn
  s = txn->RollbackToSavePoint();
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Put("B", "b");
  ASSERT_OK(s);

  s = txn->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "B", &value);
  ASSERT_OK(s);
  ASSERT_EQ("b", value);

  delete txn;
  txn = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn);

  s = txn->Put("A", "a");
  ASSERT_OK(s);

  s = txn->Put("B", "bb");
  ASSERT_OK(s);

  s = txn->Put("C", "c");
  ASSERT_OK(s);

  txn->SetSavePoint();  // 2

  s = txn->Delete("B");
  ASSERT_OK(s);

  s = txn->Put("C", "cc");
  ASSERT_OK(s);

  s = txn->Put("D", "d");
  ASSERT_OK(s);

  ASSERT_OK(txn->RollbackToSavePoint());  // Rollback to 2

  s = txn->Get(read_options, "A", &value);
  ASSERT_OK(s);
  ASSERT_EQ("a", value);

  s = txn->Get(read_options, "B", &value);
  ASSERT_OK(s);
  ASSERT_EQ("bb", value);

  s = txn->Get(read_options, "C", &value);
  ASSERT_OK(s);
  ASSERT_EQ("c", value);

  s = txn->Get(read_options, "D", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Put("A", "a");
  ASSERT_OK(s);

  s = txn->Put("E", "e");
  ASSERT_OK(s);

  // Rollback to beginning of txn
  s = txn->RollbackToSavePoint();
  ASSERT_TRUE(s.IsNotFound());
  txn->Rollback();

  s = txn->Get(read_options, "A", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Get(read_options, "B", &value);
  ASSERT_OK(s);
  ASSERT_EQ("b", value);

  s = txn->Get(read_options, "D", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Get(read_options, "D", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Get(read_options, "E", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Put("A", "aa");
  ASSERT_OK(s);

  s = txn->Put("F", "f");
  ASSERT_OK(s);

  txn->SetSavePoint();  // 3
  txn->SetSavePoint();  // 4

  s = txn->Put("G", "g");
  ASSERT_OK(s);

  s = txn->Delete("F");
  ASSERT_OK(s);

  s = txn->Delete("B");
  ASSERT_OK(s);

  s = txn->Get(read_options, "A", &value);
  ASSERT_OK(s);
  ASSERT_EQ("aa", value);

  s = txn->Get(read_options, "F", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Get(read_options, "B", &value);
  ASSERT_TRUE(s.IsNotFound());

  ASSERT_OK(txn->RollbackToSavePoint());  // Rollback to 3

  s = txn->Get(read_options, "F", &value);
  ASSERT_OK(s);
  ASSERT_EQ("f", value);

  s = txn->Get(read_options, "G", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = txn->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "F", &value);
  ASSERT_OK(s);
  ASSERT_EQ("f", value);

  s = db->Get(read_options, "G", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = db->Get(read_options, "A", &value);
  ASSERT_OK(s);
  ASSERT_EQ("aa", value);

  s = db->Get(read_options, "B", &value);
  ASSERT_OK(s);
  ASSERT_EQ("b", value);

  s = db->Get(read_options, "C", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = db->Get(read_options, "D", &value);
  ASSERT_TRUE(s.IsNotFound());

  s = db->Get(read_options, "E", &value);
  ASSERT_TRUE(s.IsNotFound());

  delete txn;
}

TEST_F(OptimisticTransactionTest, UndoGetForUpdateTest) {
  WriteOptions write_options;
  ReadOptions read_options, snapshot_read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  ASSERT_OK(db->Put(write_options, "A", ""));

  Transaction* txn1 = txn_db->BeginTransaction(write_options);
  ASSERT_TRUE(txn1);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->UndoGetForUpdate("A");

  Transaction* txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 can commit since A isn't conflict checked
  s = txn1->Commit();
  ASSERT_OK(s);
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn1->Put("A", "a"));

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 cannot commit since A will still be conflict checked
  s = txn1->Commit();
  ASSERT_TRUE(s.IsBusy());
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);
  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 cannot commit since A will still be conflict checked
  s = txn1->Commit();
  ASSERT_TRUE(s.IsBusy());
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);
  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->UndoGetForUpdate("A");
  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 can commit since A isn't conflict checked
  s = txn1->Commit();
  ASSERT_OK(s);
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->SetSavePoint();
  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 cannot commit since A will still be conflict checked
  s = txn1->Commit();
  ASSERT_TRUE(s.IsBusy());
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->SetSavePoint();
  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);
  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 cannot commit since A will still be conflict checked
  s = txn1->Commit();
  ASSERT_TRUE(s.IsBusy());
  delete txn1;

  txn1 = txn_db->BeginTransaction(write_options);

  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);

  txn1->SetSavePoint();
  s = txn1->GetForUpdate(read_options, "A", &value);
  ASSERT_OK(s);
  txn1->UndoGetForUpdate("A");

  ASSERT_OK(txn1->RollbackToSavePoint());
  txn1->UndoGetForUpdate("A");

  txn2 = txn_db->BeginTransaction(write_options);
  ASSERT_OK(txn2->Put("A", "x"));
  s = txn2->Commit();
  ASSERT_OK(s);
  delete txn2;

  // Verify that txn1 can commit since A isn't conflict checked
  s = txn1->Commit();
  ASSERT_OK(s);
  delete txn1;
}

TEST_F(OptimisticTransactionTest, ReinitializeTest) {
  WriteOptions write_options;
  ReadOptions read_options;
  OptimisticTransactionOptions txn_options;
  string value;
  Status s;

  Transaction* txn1 = txn_db->BeginTransaction(write_options, txn_options);

  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);

  s = txn1->Put("Z", "z");
  ASSERT_OK(s);

  s = txn1->Commit();
  ASSERT_OK(s);

  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);

  s = txn1->Put("Z", "zz");
  ASSERT_OK(s);

  // Reinitilize txn1 and verify that zz is not written
  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);

  s = txn1->Commit();
  ASSERT_OK(s);
  s = db->Get(read_options, "Z", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "z");

  // Verify snapshots get reinitialized correctly
  txn1->SetSnapshot();
  s = txn1->Put("Z", "zzzz");
  ASSERT_OK(s);

  s = txn1->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "Z", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "zzzz");

  const Snapshot* snapshot = txn1->GetSnapshot();
  ASSERT_TRUE(snapshot);

  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);
  snapshot = txn1->GetSnapshot();
  ASSERT_FALSE(snapshot);

  txn_options.set_snapshot = true;
  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);
  snapshot = txn1->GetSnapshot();
  ASSERT_TRUE(snapshot);

  s = txn1->Put("Z", "a");
  ASSERT_OK(s);

  txn1->Rollback();

  s = txn1->Put("Y", "y");
  ASSERT_OK(s);

  txn_options.set_snapshot = false;
  txn1 = txn_db->BeginTransaction(write_options, txn_options, txn1);
  snapshot = txn1->GetSnapshot();
  ASSERT_FALSE(snapshot);

  s = txn1->Put("X", "x");
  ASSERT_OK(s);

  s = txn1->Commit();
  ASSERT_OK(s);

  s = db->Get(read_options, "Z", &value);
  ASSERT_OK(s);
  ASSERT_EQ(value, "zzzz");

  s = db->Get(read_options, "Y", &value);
  ASSERT_TRUE(s.IsNotFound());

  delete txn1;
}

}  // namespace rocksdb

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}

#else
#include <stdio.h>

int main(int argc, char** argv) {
  fprintf(
      stderr,
      "SKIPPED as optimistic_transaction is not supported in ROCKSDB_LITE\n");
  return 0;
}

#endif  // !ROCKSDB_LITE
