// Copyright (c) YugabyteDB, Inc.
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

#pragma once

#include <memory>

#include "yb/util/result.h"

#include "yb/vector/hnsw_options.h"
#include "yb/vector/coordinate_types.h"
#include "yb/vector/vector_index_if.h"
#include "yb/vector/vector_index_wrapper_util.h"

namespace yb::vectorindex {

namespace detail {
template<IndexableVectorType Vector, ValidDistanceResultType DistanceResult>
class HnswlibIndexImpl;
}  // namespace detail

template<IndexableVectorType Vector, ValidDistanceResultType DistanceResult>
class HnswlibIndex : public VectorIndexBase<
    detail::HnswlibIndexImpl<Vector, DistanceResult>, Vector, DistanceResult> {
 public:
  explicit HnswlibIndex(const HNSWOptions& options);
  virtual ~HnswlibIndex();
 private:
  using Impl = detail::HnswlibIndexImpl<Vector, DistanceResult>;
};

template<IndexableVectorType Vector, ValidDistanceResultType DistanceResult>
class HnswlibIndexFactory : public VectorIndexFactory<Vector, DistanceResult> {
 public:
  HnswlibIndexFactory() = default;

  std::unique_ptr<VectorIndexIf<Vector, DistanceResult>> Create() const override {
    return std::make_unique<HnswlibIndex<Vector, DistanceResult>>(this->hnsw_options_);
  }
};

}  // namespace yb::vectorindex
