#include "snpe_runner.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <memory>
#include <vector>
#include "DlContainer/IDlContainer.hpp"
#include "DlSystem/DlError.hpp"
#include "DlSystem/DlEnums.hpp"
#include "DlSystem/IUserBuffer.hpp"
#include "DlSystem/IUserBufferFactory.hpp"
#include "DlSystem/RuntimeList.hpp"
#include "DlSystem/StringList.hpp"
#include "DlSystem/TensorShape.hpp"
#include "DlSystem/UserBufferMap.hpp"
#include "SNPE/SNPE.hpp"
#include "SNPE/SNPEBuilder.hpp"
#include "SNPE/SNPEFactory.hpp"

namespace {
// Массивы символов, а не указателей: глобальные массивы указателей в этом тулчейне приходили испорченными.
const char kBox[3][16] = {"conv2d_51", "conv2d_59", "conv2d_67"};
const char kCls[3][16] = {"conv2d_56", "conv2d_64", "conv2d_72"};
const int kH[3] = {64, 32, 16}, kW[3] = {128, 64, 32};
const int IN_W = 1024, IN_H = 512;

struct Impl {
    std::unique_ptr<zdl::DlContainer::IDlContainer> dlc;
    std::unique_ptr<zdl::SNPE::SNPE> snpe;
    std::vector<std::unique_ptr<zdl::DlSystem::IUserBuffer>> bufs;
    std::vector<std::vector<float>> outs;  // 0..2 box, 3..5 cls
    zdl::DlSystem::UserBufferMap in_map, out_map;
    std::unique_ptr<zdl::DlSystem::UserBufferEncodingTf8> in_enc;
    zdl::DlSystem::UserBufferEncodingFloat out_enc;
};

void print_err(const char* what) {
    fprintf(stderr, "[snpe] %s: %s\n", what, zdl::DlSystem::getLastErrorString());
}

std::vector<uint8_t> read_file(const char* path) {
    std::vector<uint8_t> d;
    FILE* f = fopen(path, "rb");
    if (!f) return d;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    d.resize(n);
    if (fread(d.data(), 1, n, f) != (size_t)n) d.clear();
    fclose(f);
    return d;
}
}  // namespace

bool SnpeRunner::init(const char* dlc_path, int runtime) {
    Impl* p = new Impl;
    impl_ = p;
    auto ver = zdl::SNPE::SNPEFactory::getLibraryVersion();
    fprintf(stderr, "[snpe] library %s\n", ver.asString().c_str());

    std::vector<uint8_t> blob = read_file(dlc_path);
    if (blob.empty()) { fprintf(stderr, "[snpe] cannot read %s\n", dlc_path); return false; }
    p->dlc = zdl::DlContainer::IDlContainer::open(blob.data(), blob.size());
    if (!p->dlc) { print_err("open dlc"); return false; }

    zdl::DlSystem::Runtime_t rt = runtime == 0 ? zdl::DlSystem::Runtime_t::DSP : zdl::DlSystem::Runtime_t::CPU;
    if (!zdl::SNPE::SNPEFactory::isRuntimeAvailable(rt)) { fprintf(stderr, "[snpe] runtime %d unavailable\n", runtime); return false; }
    zdl::DlSystem::RuntimeList rl;
    rl.add(rt);
    zdl::DlSystem::StringList outs;
    for (int i = 0; i < 3; i++) { outs.append(kBox[i]); outs.append(kCls[i]); }

    zdl::SNPE::SNPEBuilder b(p->dlc.get());
    p->snpe = b.setRuntimeProcessorOrder(rl)
                  .setOutputTensors(outs)
                  .setUseUserSuppliedBuffers(true)
                  .setPerformanceProfile(zdl::DlSystem::PerformanceProfile_t::BALANCED)
                  .setCPUFallbackMode(false)
                  .build();
    if (!p->snpe) { print_err("build"); return false; }

    auto& fac = zdl::SNPE::SNPEFactory::getUserBufferFactory();
    // Вход: tf8 с той же шкалой, что у входного слоя DLC (min 0, шаг 1/255.5 ~ 1/255).
    const auto& in_names = p->snpe->getInputTensorNames();
    const char* in_name = (*in_names).at(0);
    {
        void* mem = nullptr;
        if (posix_memalign(&mem, 64, IN_W * IN_H * 3) != 0) { fprintf(stderr, "[snpe] no memory\n"); return false; }
        in_ = (uint8_t*)mem;
    }
    p->in_enc.reset(new zdl::DlSystem::UserBufferEncodingTf8(0, 1.0f / 255.0f));
    {
        zdl::DlSystem::TensorShape strides({(size_t)IN_H * IN_W * 3, (size_t)IN_W * 3, 3, 1});
        p->bufs.push_back(fac.createUserBuffer(in_, IN_W * IN_H * 3, strides, p->in_enc.get()));
        if (!p->bufs.back()) { print_err("input userbuffer"); return false; }
        p->in_map.add(in_name, p->bufs.back().get());
    }
    p->outs.resize(6);
    for (int k = 0; k < 6; k++) {
        int s = k % 3;
        const char* name = k < 3 ? kBox[s] : kCls[s];
        auto attr = p->snpe->getInputOutputBufferAttributes(name);
        if (!attr) { fprintf(stderr, "[snpe] no attributes for %s\n", name); return false; }
        const zdl::DlSystem::TensorShape& dims = (*attr)->getDims();
        size_t rank = dims.rank(), n = 1;
        std::vector<size_t> st(rank);
        for (size_t i = rank; i-- > 0;) { st[i] = n * 4; n *= dims[i]; }
        fprintf(stderr, "[snpe] output %s rank %zu dims", name, rank);
        for (size_t i = 0; i < rank; i++) fprintf(stderr, " %zu", dims[i]);
        fprintf(stderr, "\n");
        int c = k < 3 ? 64 : 4;
        if (n != (size_t)kH[s] * kW[s] * c) { fprintf(stderr, "[snpe] unexpected size for %s\n", name); return false; }
        p->outs[k].resize(n);
        zdl::DlSystem::TensorShape strides(st.data(), rank);
        p->bufs.push_back(fac.createUserBuffer(p->outs[k].data(), n * 4, strides, &p->out_enc));
        if (!p->bufs.back()) { print_err("output userbuffer"); return false; }
        p->out_map.add(name, p->bufs.back().get());
    }
    {
        auto on = p->snpe->getOutputTensorNames();
        for (size_t i = 0; i < (*on).size(); i++) fprintf(stderr, "[snpe] net output %zu: %s\n", i, (*on).at(i));
        auto bn = p->out_map.getUserBufferNames();
        for (size_t i = 0; i < bn.size(); i++) fprintf(stderr, "[snpe] mapped output %zu: %s\n", i, bn.at(i));
    }
    fprintf(stderr, "[snpe] ready: input '%s', runtime %s\n", in_name, runtime == 0 ? "DSP" : "CPU");
    return true;
}

bool SnpeRunner::run(const uint8_t* rgb, HeadMaps* out) {
    Impl* p = (Impl*)impl_;
    if (rgb != in_) memcpy(in_, rgb, IN_W * IN_H * 3);
    if (!p->snpe->execute(p->in_map, p->out_map)) { print_err("execute"); return false; }
    for (int s = 0; s < 3; s++) {
        out->box[s] = p->outs[s].data();
        out->cls[s] = p->outs[3 + s].data();
        out->h[s] = kH[s];
        out->w[s] = kW[s];
    }
    return true;
}

SnpeRunner::~SnpeRunner() {
    delete (Impl*)impl_;
    free(in_);
}
