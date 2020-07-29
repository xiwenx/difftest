package xiangshan.mem.pipeline

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import chisel3.util.experimental.BoringUtils
import xiangshan.backend.decode.XSTrap
import xiangshan.mem._
import xiangshan.mem.cache._
import bus.simplebus._

class LsRoqEntry extends XSBundle {
  val paddr = UInt(PAddrBits.W)
  val op = UInt(6.W)
  val mask = UInt(8.W)
  val data = UInt(XLEN.W)
  val exception = UInt(8.W)
  // val miss = Bool()
  val mmio = Bool()
  // val store = Bool()
  val bwdMask = Vec(8, Bool()) // UInt(8.W)
  val bwdData = Vec(8, UInt(8.W))
}

// Load/Store Roq (Moq) for XiangShan Out of Order LSU
class Lsroq(implicit val p: XSConfig) extends XSModule with HasMEMConst {
  val io = IO(new Bundle() {
    val dp1Req = Vec(RenameWidth, Flipped(DecoupledIO(new MicroOp)))
    val moqIdxs = Output(Vec(RenameWidth, UInt(MoqIdxWidth.W)))
    val brqRedirect = Input(Valid(new Redirect))
    val loadIn = Vec(LoadPipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val storeIn = Vec(StorePipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val sbuffer = Vec(StorePipelineWidth, Decoupled(new DCacheStoreReq))
    val ldout = Vec(2, DecoupledIO(new ExuOutput)) // writeback store
    val stout = Vec(2, DecoupledIO(new ExuOutput)) // writeback store
    val mcommit = Input(UInt(3.W))
    val forward = Vec(LoadPipelineWidth, Flipped(new LoadForwardQueryIO))
    val rollback = Output(Valid(new Redirect))
    val miss = Decoupled(new MissReqIO)
  })

  val uop = Mem(LSRoqSize, new MicroOp)
  val data = Reg(Vec(LSRoqSize, new LsRoqEntry))
  val allocated = RegInit(VecInit(List.fill(MoqSize)(false.B))) // lsroq entry has been allocated
  val valid = RegInit(VecInit(List.fill(MoqSize)(false.B))) // data is valid
  val writebacked = RegInit(VecInit(List.fill(MoqSize)(false.B))) // inst has been writebacked to CDB
  val store = Reg(Vec(MoqSize, Bool())) // inst is a store inst
  val miss = Reg(Vec(MoqSize, Bool())) // load inst missed, waiting for miss queue to accept miss request
  val listening = Reg(Vec(MoqSize, Bool())) // waiting foe refill result

  val ringBufferHeadExtended = RegInit(0.U(MoqIdxWidth.W))
  val ringBufferTailExtended = RegInit(0.U(MoqIdxWidth.W))
  val ringBufferHead = ringBufferHeadExtended(InnerRoqIdxWidth-1,0)
  val ringBufferTail = ringBufferTailExtended(InnerRoqIdxWidth-1,0)
  val ringBufferEmpty = ringBufferHead === ringBufferTail && ringBufferHeadExtended(InnerMoqIdxWidth)===ringBufferTailExtended(InnerMoqIdxWidth)
  val ringBufferFull = ringBufferHead === ringBufferTail && ringBufferHeadExtended(InnerMoqIdxWidth)=/=ringBufferTailExtended(InnerMoqIdxWidth)
  val ringBufferAllowin = !ringBufferFull

  // Enqueue at dispatch
  val validDispatch = VecInit((0 until RenameWidth).map(io.dp1Req(_).valid)).asUInt
  XSDebug("(ready, valid): ")
  for (i <- 0 until RenameWidth) {
    val offset = if(i==0) 0.U else PopCount(validDispatch(i-1,0))
    when(io.dp1Req(i).fire()){
      uop(ringBufferHead+offset) := io.dp1Req(i).bits
      allocated(ringBufferHead+offset) := true.B
      valid(ringBufferHead+offset) := false.B
      writebacked(ringBufferHead+offset) := false.B
      store(ringBufferHead+offset) := false.B
      miss(ringBufferHead+offset) := false.B
      listening(ringBufferHead+offset) := false.B
      data(ringBufferHead+offset).bwdMask := 0.U(8.W).asBools
    }
    io.dp1Req(i).ready := ringBufferAllowin && !allocated(ringBufferHead+offset)
    io.moqIdxs(i) := ringBufferHeadExtended+offset
    XSDebug(false, true.B, "(%d, %d) ", io.dp1Req(i).ready, io.dp1Req(i).valid)
  }
  XSDebug(false, true.B, "\n")

  val firedDispatch = VecInit((0 until CommitWidth).map(io.dp1Req(_).fire())).asUInt
  when(firedDispatch.orR){
    ringBufferHeadExtended := ringBufferHeadExtended + PopCount(firedDispatch)
    XSInfo("dispatched %d insts to moq\n", PopCount(firedDispatch))
  }

  // misprediction recovery / exception redirect
  // invalidate lsroq term using robIdx
  // TODO: check exception redirect implementation
  (0 until MoqSize).map(i => {
    when(uop(i).brTag.needFlush(io.brqRedirect) && allocated(i)){
      allocated(i) := false.B
    }
  })

  // writeback load
  (0 until LoadPipelineWidth).map(i => {
    assert(!io.loadIn(i).bits.miss)
    when(io.loadIn(i).fire()){
      when(io.loadIn(i).bits.miss){
        XSInfo(io.loadIn(i).valid, "load miss write to cbd idx %d pc 0x%x vaddr %x paddr %x data %x mmio %x roll %x\n",
          io.loadIn(i).bits.uop.moqIdx,
          io.loadIn(i).bits.uop.cf.pc,
          io.loadIn(i).bits.vaddr,
          io.loadIn(i).bits.paddr,
          io.loadIn(i).bits.data,
          io.loadIn(i).bits.mmio,
          io.loadIn(i).bits.rollback
        )
      }.otherwise{
        XSInfo(io.loadIn(i).valid, "load hit write to cbd idx %d pc 0x%x vaddr %x paddr %x data %x mmio %x roll %x\n",
          io.loadIn(i).bits.uop.moqIdx,
          io.loadIn(i).bits.uop.cf.pc,
          io.loadIn(i).bits.vaddr,
          io.loadIn(i).bits.paddr,
          io.loadIn(i).bits.data,
          io.loadIn(i).bits.mmio,
          io.loadIn(i).bits.rollback
        )
      }
      valid(io.loadIn(i).bits.uop.moqIdx) := !io.loadIn(i).bits.miss
      writebacked(io.loadIn(i).bits.uop.moqIdx) := !io.loadIn(i).bits.miss
      data(io.loadIn(i).bits.uop.moqIdx).paddr := io.loadIn(i).bits.paddr
      data(io.loadIn(i).bits.uop.moqIdx).mask := io.loadIn(i).bits.mask
      data(io.loadIn(i).bits.uop.moqIdx).data := io.loadIn(i).bits.data
      data(io.loadIn(i).bits.uop.moqIdx).mmio := io.loadIn(i).bits.mmio
      miss(io.loadIn(i).bits.uop.moqIdx) := io.loadIn(i).bits.miss
      store(io.loadIn(i).bits.uop.moqIdx) := false.B
    }
  })

  // writeback store
  (0 until StorePipelineWidth).map(i => {
    when(io.storeIn(i).fire()){
      valid(io.storeIn(i).bits.uop.moqIdx) := true.B
      data(io.storeIn(i).bits.uop.moqIdx).paddr := io.storeIn(i).bits.paddr
      data(io.storeIn(i).bits.uop.moqIdx).mask := io.storeIn(i).bits.mask
      data(io.storeIn(i).bits.uop.moqIdx).data := io.storeIn(i).bits.data
      data(io.storeIn(i).bits.uop.moqIdx).mmio := io.storeIn(i).bits.mmio
      miss(io.storeIn(i).bits.uop.moqIdx) := io.storeIn(i).bits.miss
      store(io.storeIn(i).bits.uop.moqIdx) := true.B
      XSInfo("store write to lsroq idx %d pc 0x%x vaddr %x paddr %x miss %x mmio %x roll %x\n",
        io.storeIn(i).bits.uop.moqIdx,
        io.storeIn(i).bits.uop.cf.pc,
        io.storeIn(i).bits.vaddr,
        io.storeIn(i).bits.paddr,
        io.storeIn(i).bits.miss,
        io.storeIn(i).bits.mmio,
        io.storeIn(i).bits.rollback
      )
    }
  })

  // cache miss request
  val missRefillSelVec = VecInit(
    (0 until MoqSize).map(i => allocated(i) && miss(i))
  )
  val missRefillSel = OHToUInt(missRefillSelVec.asUInt)
  io.miss.valid := missRefillSelVec.asUInt.orR
  io.miss.bits.paddr := data(missRefillSel).paddr
  when(io.miss.fire()){
    miss(missRefillSel) := false.B
    listening(missRefillSel) := true.B
  }

  // get load result from refill resp
  // TODO
  // (0 until MoqSize).map(i => {
  //   when(addr_match){
  //     data(i).data := dataIn
  //     valid(i) := true.B
  //     listening(i) := false.B
  //   }
  // })

  // writeback up to 2 missed load insts to CDB
  // just randomly pick 2 missed load (data refilled), write them back to cdb
  val loadWbSelVec = VecInit((0 until MoqSize).map(i => {
    allocated(i) && valid(i) && !writebacked(i) && !store(i)
  }))
  val loadWbSel = Wire(Vec(StorePipelineWidth, UInt(log2Up(MoqSize).W)))
  val lselvec0 = VecInit(PriorityEncoderOH(loadWbSelVec))
  val lselvec1 = VecInit(PriorityEncoderOH(loadWbSelVec.asUInt & ~lselvec0.asUInt))
  loadWbSel(0) := OHToUInt(lselvec0.asUInt)
  loadWbSel(1) := OHToUInt(lselvec1.asUInt)
  (0 until StorePipelineWidth).map(i => {
    io.ldout(i).bits.uop := uop(loadWbSel(i))
    io.ldout(i).bits.data := data(loadWbSel(i)).data
    io.ldout(i).bits.redirectValid := false.B
    io.ldout(i).bits.redirect := DontCare
    io.ldout(i).bits.brUpdate := DontCare
    io.ldout(i).bits.debug.isMMIO := data(loadWbSel(i)).mmio
    when(loadWbSelVec(loadWbSel(i))){
      writebacked(loadWbSel(i)) := true.B
    }
    io.ldout(i).valid := loadWbSelVec(loadWbSel(i))
    when(io.ldout(i).fire()){
      writebacked(loadWbSel(i)) := true.B
    }
  })

  // writeback up to 2 store insts to CDB
  // just randomly pick 2 stores, write them back to cdb
  val storeWbSelVec = VecInit((0 until MoqSize).map(i => {
    allocated(i) && valid(i) && !writebacked(i) && store(i)
  }))
  val storeWbSel = Wire(Vec(StorePipelineWidth, UInt(log2Up(MoqSize).W)))
  val sselvec0 = VecInit(PriorityEncoderOH(storeWbSelVec))
  val sselvec1 = VecInit(PriorityEncoderOH(storeWbSelVec.asUInt & ~sselvec0.asUInt))
  storeWbSel(0) := OHToUInt(sselvec0.asUInt)
  storeWbSel(1) := OHToUInt(sselvec1.asUInt)

  (0 until StorePipelineWidth).map(i => {
    io.stout(i).bits.uop := uop(storeWbSel(i))
    io.stout(i).bits.data := data(storeWbSel(i)).data
    io.stout(i).bits.redirectValid := false.B
    io.stout(i).bits.redirect := DontCare
    io.stout(i).bits.brUpdate := DontCare
    io.stout(i).bits.debug.isMMIO := data(storeWbSel(i)).mmio
    when(storeWbSelVec(storeWbSel(i))){
      writebacked(storeWbSel(i)) := true.B
    }
    io.stout(i).valid := storeWbSelVec(storeWbSel(i))
    when(io.stout(i).fire()){
      writebacked(storeWbSel(i)) := true.B
    }
  })

  // remove retired insts from lsroq, add retired store to sbuffer
  val scommitCnt = RegInit(0.U(log2Up(MoqSize).W))
  val demoqCnt = WireInit(0.U(3.W)) // seems not enough

  // check insts at the tail of lsroq
  // no more than 2 commited store insts can be sent to sbuffer
  var demoqStoreCnt = WireInit(0.U(2.W))
  var demoqSucceedCnt = WireInit(0.U(3.W))
  var demoqFreeEntryCnt = WireInit(0.U(3.W))
  var demoqLegal = WireInit(true.B)

  // Lsroq -> sbuffer width: 2
  (0 until 2).map(i => {
    io.sbuffer(i) := DontCare //ignore higher bits of DCacheStoreReq data/mask
    io.sbuffer(i).valid := false.B
  })

  demoqLegal = WireInit(ringBufferTailExtended =/= ringBufferHeadExtended)
  // TODO: add width to 4? 6?
  // FIXME
  (0 until 2).map(i => {
    val ptrExt = ringBufferTailExtended + i.U
    val ptr = ptrExt(InnerRoqIdxWidth-1, 0)
    val isValidRetire = demoqSucceedCnt < scommitCnt && allocated(ptr) && valid(ptr) && writebacked(ptr) && !miss(ptr)
    val isCanceled = !allocated(ptr)

    io.sbuffer(i).valid := store(ptr) && isValidRetire && demoqLegal
    io.sbuffer(i).bits.paddr := data(ptr).paddr
    io.sbuffer(i).bits.data := data(ptr).data
    io.sbuffer(i).bits.mask := data(ptr).mask
    io.sbuffer(i).bits.miss := false.B
    io.sbuffer(i).bits.user.uop := uop(ptr)
    io.sbuffer(i).bits.user.mmio := data(ptr).mmio
    io.sbuffer(i).bits.user.mask := data(ptr).mask
    io.sbuffer(i).bits.user.id := DontCare // always store
    io.sbuffer(i).bits.user.paddr := DontCare

    when(store(ptr)){
      demoqStoreCnt = WireInit(demoqStoreCnt + Mux(demoqStoreCnt >= 2.U, 0.U, io.sbuffer(demoqStoreCnt(0)).ready.asUInt))
    }

    when((isValidRetire || isCanceled) && demoqLegal){
      demoqFreeEntryCnt = WireInit(demoqFreeEntryCnt + 1.U)
      demoqSucceedCnt = WireInit(demoqSucceedCnt + isValidRetire.asUInt)
      val sbufferFull = store(ptr) && !io.sbuffer(0).ready
      demoqLegal = WireInit(demoqLegal && !sbufferFull)
      allocated(i) := false.B // FIXME: for debug only
    }
    //  && !sbufferFull
  })

  demoqCnt := demoqSucceedCnt
  ringBufferTailExtended := ringBufferTailExtended + demoqFreeEntryCnt
  scommitCnt := scommitCnt + io.mcommit - demoqCnt

  // load forward query
  // // left.age < right.age
  // def moqIdxOlderThan (left: UInt, right: UInt): Bool = {
  //   require(left.getWidth == MoqIdxWidth)
  //   require(right.getWidth == MoqIdxWidth)
  //   Mux(left(InnerMoqIdxWidth) === right(InnerMoqIdxWidth),
  //     left(InnerMoqIdxWidth-1, 0) > right(InnerMoqIdxWidth-1, 0),
  //     left(InnerMoqIdxWidth-1, 0) < right(InnerMoqIdxWidth-1, 0)
  //   )
  // }

  (0 until LoadPipelineWidth).map(i => {
    io.forward(i).forwardMask := 0.U(8.W).asBools
    io.forward(i).forwardData := DontCare
    // Just for functional simulation

    // forward
    val needForward1 = WireInit(VecInit((0 until MoqSize).map(j => {
      io.forward(i).moqIdx(InnerMoqIdxWidth-1, 0) > j.U &&
      (
        ringBufferTail <= j.U || 
        ringBufferTailExtended(InnerMoqIdxWidth) =/= io.forward(i).moqIdx(InnerMoqIdxWidth)
        ) 
      })))
      val needForward2 = WireInit(VecInit((0 until MoqSize).map(j => {
        ringBufferTail <= j.U &&
        ringBufferTailExtended(InnerMoqIdxWidth) =/= io.forward(i).moqIdx(InnerMoqIdxWidth)
      })))
      val forwardMask1 = WireInit(VecInit(Seq.fill(8)(false.B)))
      val forwardData1 = WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
      val forwardMask2 = WireInit(VecInit(Seq.fill(8)(false.B)))
      val forwardData2 = WireInit(VecInit(Seq.fill(8)(0.U(8.W))))
      
      // forward lookup vec2
      (0 until MoqSize).map(j => {
        when(
          needForward2(j) &&
          valid(j) && allocated(j) && store(j) &&
          io.forward(i).paddr(PAddrBits-1, 3) === data(j).paddr(PAddrBits-1, 3)
        ){
          (0 until 8).map(k => {
              when(data(j).mask(k)){
                forwardMask2(k) := true.B
                forwardData2(k) := data(j).data(8*(k+1)-1, 8*k)
                XSDebug("forwarding "+k+"th byte %x from ptr %d pc %x\n",
                data(j).data(8*(k+1)-1, 8*k), j.U, uop(j).cf.pc
                )
              }
            })
          }
      })
      // forward lookup vec1
      (0 until MoqSize).map(j => {
        when(
        needForward1(j) &&
        valid(j) && allocated(j) && store(j) &&
        io.forward(i).paddr(PAddrBits-1, 3) === data(j).paddr(PAddrBits-1, 3)
      ){
        (0 until 8).map(k => {
            when(data(j).mask(k)){
              forwardMask1(k) := true.B
              forwardData1(k) := data(j).data(8*(k+1)-1, 8*k)
              XSDebug("forwarding "+k+"th byte %x from ptr %d pc %x\n",
              data(j).data(8*(k+1)-1, 8*k), j.U, uop(j).cf.pc
              )
            }
          })
        }
    })
    // merge forward lookup results
    (0 until 8).map(k => {
      io.forward(i).forwardMask(k) := forwardMask1(k) || forwardMask2(k)
      io.forward(i).forwardData(k) := Mux(forwardMask1(k), forwardData1(k), forwardData2(k))
    })

    // (1 until MoqSize).map(j => {
    //   val ptr = io.forward(i).moqIdx - j.U
    //   when(
    //     moqIdxOlderThan(ptr, io.forward(i).moqIdx) &&
    //     valid(ptr) && allocated(ptr) && store(ptr) &&
    //     io.forward(i).paddr(PAddrBits-1, 3) === data(ptr).paddr(PAddrBits-1, 3)
    //   ){
    //     (0 until 8).map(k => {
    //       // when(data(ptr).mask(k) && io.forward(i).mask(k)){
    //         when(data(ptr).mask(k)){
    //           io.forward(i).forwardMask(k) := true.B
    //           io.forward(i).forwardData(k) := data(ptr).data(8*(k+1)-1, 8*k)
    //           XSDebug("forwarding "+k+"th byte %x from ptr %d pc %x\n",
    //           io.forward(i).forwardData(k), ptr, uop(ptr).cf.pc
    //           )
    //         }
    //       })
    //     }
    //   })
      
    // backward
    (0 until 8).map(k => {
      when(data(io.forward(i).moqIdx).bwdMask(k)){
        io.forward(i).forwardMask(k) := true.B
        io.forward(i).forwardData(k) := data(io.forward(i).moqIdx).bwdData(k)
        XSDebug("backwarding "+k+"th byte %x\n", io.forward(i).forwardData(k))
      }
    })
  })

  val rollback = Wire(Vec(StorePipelineWidth, Valid(new Redirect)))

  // store backward query and rollback
  val needCheck = Seq.fill(8)(WireInit(true.B))
  (0 until StorePipelineWidth).map(i => {
    rollback(i) := DontCare
    rollback(i).valid := false.B
    when(io.storeIn(i).valid){
      val needCheck = Seq.fill(MoqSize+1)(Seq.fill(8)(WireInit(true.B)))
      (1 until MoqSize).map(j => {
        val ptr = io.forward(i).moqIdx + j.U
        val reachHead = ptr === ringBufferHeadExtended
        val addrMatch = allocated(ptr) &&
        io.storeIn(i).bits.paddr(PAddrBits-1, 3) === data(ptr).paddr(PAddrBits-1, 3)
        val mask = data(ptr).mask
        val _store = store(ptr)
        val _writebacked = writebacked(ptr)
        (0 until 8).map(k => {
          when(needCheck(j)(k) && addrMatch && mask(k) && io.storeIn(i).bits.mask(k) && !_store){
            when(_writebacked){
              rollback(i).valid := true.B
              rollback(i).bits.roqIdx := io.storeIn(i).bits.uop.roqIdx
              rollback(i).bits.target := io.storeIn(i).bits.uop.cf.pc
            }.otherwise{
              data(j).bwdMask(k) := true.B
              data(j).bwdData(k) := io.storeIn(i).bits.data(8*(k+1)-1, 8*k)
              XSDebug("write backward data: ptr %x byte %x data %x\n", ptr, k.U, io.storeIn(i).bits.data(8*(k+1)-1, 8*k))
            } 
          }
          needCheck(j+1)(k) := needCheck(j)(k) && !(addrMatch && _store) && !reachHead
        })

        // when l/s writeback to roq together, check if rollback is needed
        // currently we just rollback (TODO)
        when(io.storeIn(i).valid && io.storeIn(i).bits.uop.moqIdx === ptr){
          (0 until LoadPipelineWidth).map(j => {
            when(
              io.loadIn(j).valid &&
              io.storeIn(i).bits.paddr(PAddrBits-1, 3) === io.loadIn(j).bits.paddr(PAddrBits-1, 3) &&
              (io.storeIn(i).bits.mask & io.loadIn(j).bits.mask).orR
            ){
              rollback(i).valid := true.B
              rollback(i).bits.target := io.storeIn(i).bits.uop.cf.pc
              rollback(i).bits.roqIdx := io.storeIn(i).bits.uop.roqIdx
            }
          })
        }

      })
    }
  })

  val rollRoqIdx = (0 until StorePipelineWidth).map(i => {
    rollback(i).bits.roqIdx
  })

  // FIXME: this is ugly
  val rollbackSel = Mux(
    rollback(0).valid && rollback(1).valid,
    Mux(rollRoqIdx(0)(InnerRoqIdxWidth) === rollRoqIdx(0)(InnerRoqIdxWidth),
      rollRoqIdx(0)(InnerRoqIdxWidth-1, 0) > rollRoqIdx(0)(InnerRoqIdxWidth-1, 0),
      rollRoqIdx(0)(InnerRoqIdxWidth-1, 0) < rollRoqIdx(0)(InnerRoqIdxWidth-1, 0)
    ),
    rollback(1).valid
  )

  io.rollback := rollback(rollbackSel)
  assert(!io.rollback.valid)

  // debug info
  XSDebug("head %d:%d tail %d:%d\n", ringBufferHeadExtended(InnerMoqIdxWidth), ringBufferHead, ringBufferTailExtended(InnerMoqIdxWidth), ringBufferTail)

  def PrintFlag(flag: Bool, name: String): Unit = {
    when(flag){
      XSDebug(false, true.B, name)
    }.otherwise{
      XSDebug(false, true.B, " ")
    }
  }

  for(i <- 0 until MoqSize){
    if(i % 4 == 0) XSDebug("")
    XSDebug(false, true.B, "%x ", uop(i).cf.pc)
    PrintFlag(allocated(i), "a")
    PrintFlag(valid(i), "v")
    PrintFlag(writebacked(i), "w")
    PrintFlag(store(i), "s")
    PrintFlag(miss(i), "m")
    PrintFlag(listening(i), "l")
    XSDebug(false, true.B, " ")
    if(i % 4 == 3) XSDebug(false, true.B, "\n")
  }

}
