// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
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

////////////////////////////////////////////////////////////////////////////////
// Example usage:
// protoc --plugin=protoc-gen-yrpc --yrpc_out . --proto_path . <file>.proto
////////////////////////////////////////////////////////////////////////////////

#include <functional>

#include <google/protobuf/compiler/code_generator.h>
#include <google/protobuf/compiler/plugin.h>
#include <google/protobuf/descriptor.h>
#include <google/protobuf/io/printer.h>
#include <google/protobuf/io/zero_copy_stream.h>

#include "yb/gen_yrpc/printer.h"
#include "yb/gen_yrpc/messages_generator.h"
#include "yb/gen_yrpc/proxy_generator.h"
#include "yb/gen_yrpc/service_generator.h"
#include "yb/gen_yrpc/substitutions.h"

using namespace std::placeholders;

namespace yb {
namespace gen_yrpc {

class CodeGenerator : public google::protobuf::compiler::CodeGenerator {
 public:
  CodeGenerator() { }

  ~CodeGenerator() { }

  bool Generate(const google::protobuf::FileDescriptor *file,
        const std::string& parameter,
        google::protobuf::compiler::GeneratorContext *gen_context,
        std::string *error) const override {

    std::vector<std::pair<std::string, std::string>> params_temp;
    google::protobuf::compiler::ParseGeneratorParameter(parameter, &params_temp);
    std::map<std::string, std::string> params;
    for (const auto& p : params_temp) {
      params.emplace(p.first, p.second);
    }

    FileSubstitutions name_info(file);

    SubstitutionContext subs;
    subs.Push(name_info.Create());

    if (file->service_count() != 0) {
      Generate<ServiceGenerator>(file, gen_context, &subs, name_info.service());
      Generate<ProxyGenerator>(file, gen_context, &subs, name_info.proxy());
    }

    if (params.count("messages")) {
      Generate<MessagesGenerator>(
          file, gen_context, &subs, name_info.messages());
    }

    return true;
  }

 private:
  template <class Generator>
  void Generate(
      const google::protobuf::FileDescriptor *file,
      google::protobuf::compiler::GeneratorContext *gen_context,
      SubstitutionContext *subs, const std::string& fname) const {
    Generator generator;
    DoGenerate(
        file, gen_context, subs, fname + ".h",
        std::bind(&Generator::Header, &generator, _1, _2));

    DoGenerate(
        file, gen_context, subs, fname + ".cc",
        std::bind(&Generator::Source, &generator, _1, _2));
  }

  template <class F>
  void DoGenerate(
      const google::protobuf::FileDescriptor *file,
      google::protobuf::compiler::GeneratorContext *gen_context,
      SubstitutionContext *subs, const std::string& fname, const F& generator) const {
    std::unique_ptr<google::protobuf::io::ZeroCopyOutputStream> output(gen_context->Open(fname));
    google::protobuf::io::Printer printer(output.get(), '$');
    YBPrinter yb_printer(&printer, subs);
    generator(yb_printer, file);
  }
};

} // namespace gen_yrpc
} // namespace yb

int main(int argc, char *argv[]) {
  yb::gen_yrpc::CodeGenerator generator;
  return google::protobuf::compiler::PluginMain(argc, argv, &generator);
}
