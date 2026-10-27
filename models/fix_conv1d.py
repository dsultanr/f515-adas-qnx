# Убирает пары Squeeze(rank4->3)/Unsqueeze(3->4) вокруг conv1d-как-2d:
# SNPE 1.55 не отслеживает оси у тензоров ранга 3 и падает на этих Conv.
import onnx,sys
from onnx import shape_inference
m=onnx.load(sys.argv[1]); g=m.graph
prod={o:n for n in g.node for o in n.output}
cons={}
for n in g.node:
    for i in n.input: cons.setdefault(i,[]).append(n)
drop=set(); ren={}
for n in g.node:
    if n.op_type=='Conv' and n.name.endswith('conv1d') or (n.op_type=='Conv' and n.input[0].endswith('_input_2d')):
        un=prod[n.input[0]]; sq=prod[un.input[0]]
        assert un.op_type=='Unsqueeze' and sq.op_type=='Squeeze', n.name
        assert len(cons[sq.output[0]])==1 and len(cons[un.output[0]])==1
        n.input[0]=sq.input[0]; drop|={un.name,sq.name}
        (after,)=cons[n.output[0]]; assert after.op_type=='Squeeze', after.name
        ren[after.output[0]]=n.output[0]; drop.add(after.name)
        print('fixed',n.name)
keep=[n for n in g.node if n.name not in drop]
for n in keep:
    for k,x in enumerate(n.input):
        if x in ren: n.input[k]=ren[x]
del g.node[:]; g.node.extend(keep)
del g.value_info[:]
m.ir_version=min(m.ir_version,7); m=shape_inference.infer_shapes(m); onnx.checker.check_model(m); onnx.save(m,sys.argv[2])
