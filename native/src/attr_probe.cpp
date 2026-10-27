// Диагностика: для каждого выхода сети (имена из самого SNPE) — есть ли атрибуты user buffer.
#include <stdio.h>
#include <string.h>
#include <vector>
#include "DlContainer/IDlContainer.hpp"
#include "DlSystem/DlError.hpp"
#include "DlSystem/RuntimeList.hpp"
#include "DlSystem/StringList.hpp"
#include "SNPE/SNPE.hpp"
#include "SNPE/SNPEBuilder.hpp"
#include "SNPE/SNPEFactory.hpp"
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    std::vector<unsigned char> d(n); fread(d.data(), 1, n, f); fclose(f);
    auto dlc = zdl::DlContainer::IDlContainer::open(d.data(), d.size());
    zdl::DlSystem::RuntimeList rl; rl.add(zdl::DlSystem::Runtime_t::CPU);
    zdl::DlSystem::StringList outs;
    for (int i = 2; i < argc; i++) outs.append(argv[i]);
    zdl::SNPE::SNPEBuilder b(dlc.get());
    auto snpe = b.setRuntimeProcessorOrder(rl).setOutputTensors(outs).setUseUserSuppliedBuffers(true).build();
    if (!snpe) { printf("build failed: %s\n", zdl::DlSystem::getLastErrorString()); return 1; }
    auto on = snpe->getOutputTensorNames();
    for (size_t i = 0; i < (*on).size(); i++) {
        const char* nm = (*on).at(i);
        auto a = snpe->getInputOutputBufferAttributes(nm);
        printf("out#%zu len=%zu last2=%c%c attr=%s\n", i, strlen(nm), nm[strlen(nm)-2], nm[strlen(nm)-1] == '7' ? 'S' : nm[strlen(nm)-1], a ? "yes" : "NO");
    }
    return 0;
}
