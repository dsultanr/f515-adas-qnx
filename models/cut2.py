# Как cut.py, но выходы — входы Concat голов: отдельно 64 канала DFL и 4 канала классов на каждом страйде,
# чтобы у логитов классов была своя шкала INT8 (в общем тензоре шаг 0.38 из-за диапазона DFL).
import onnx,sys,tempfile,os
m=onnx.load(sys.argv[1]); g=m.graph
prod={o:n for n in g.node for o in n.output}
outs=[]
for v in ('view_15','view_16','view_17'):
    cat=prod[prod[v].input[0]]; assert cat.op_type=='Concat'; outs+=list(cat.input)
print('outs',outs)
with tempfile.TemporaryDirectory() as tmp:
    source=os.path.join(tmp,'model.onnx')
    onnx.save(m,source)
    onnx.utils.extract_model(source,sys.argv[2],['input'],outs)
m=onnx.load(sys.argv[2]); print([(o.name,[d.dim_value for d in o.type.tensor_type.shape.dim]) for o in m.graph.output])
