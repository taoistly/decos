import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Set
import scala.collection.mutable.Map
import scala.io.Source
import java.io.RandomAccessFile

import scala.collection.mutable
import org.apache.spark._

/**
  * Created by workshop on 04-Jul-17.
  */
object MappingRead {
  def apply(idx: Long, pos: Int, file1: RandomAccessFile, file2: RandomAccessFile, complement:Boolean): MappingRead = {
    file1.seek(idx)
    file2.seek(idx)
    val seq1 = file1.readLine()
    val seq2 = file2.readLine()
    new MappingRead(idx, pos, seq1, seq2, complement)
  }
}

class MappingRead(idx: Long, pos: Int, file1: String, file2: String, complement:Boolean=false) {
  val id: Long = idx
  var complemented = complement
  var reversed = false
  var kmeridx: Int = pos
  var seq1 = file1
  var seq2 = file2
  var column1 = Array.fill(seq1.length)(-1)
  var column2 = Array.fill(seq2.length)(-1)
  if (kmeridx < 0) {
    reversed = !reversed
    kmeridx = -kmeridx
    val tmp = seq1
    seq1 = seq2
    seq2 = tmp
  }
  if (complement){
    seq1 = Util.reverseComplement(seq1)
  }
}

class ConsensusSequence(init: String) {
  var columnID = init.indices.toBuffer
  var vote = ArrayBuffer.fill(init.length)(mutable.Map('A' -> 0, 'C' -> 0, 'G' -> 0, 'T' -> 0, '-' -> 0))
  for (i <- 0 until init.length) vote(i)(init(i)) += 1

  def charAt(idx: Int): Char = vote(idx).maxBy(x => x._2 * 1000 - x._1)._1

  def addCount(idx: Int, c: Char): Unit = {
    if (c != 'N') vote(idx)(c) += 1
  }

  def sequence(keepGap: Boolean = false): String = {
    val consensusWithGap = for (i <- vote.indices) yield charAt(i)
    if (keepGap) consensusWithGap.mkString else consensusWithGap.filter(_ != '-').mkString
  }

  def insert(idx: Int, newChars: Array[Char]): Unit = {
    columnID.insertAll(idx, columnID.size until (columnID.size + newChars.length))
    val newColumn = for (c <- newChars) yield mutable.Map('A' -> 0, 'C' -> 0, 'G' -> 0, 'T' -> 0, '-' -> 0) + (c -> 1)
    vote.insertAll(idx, newColumn)
  }

  def checkGap(idx: Int, newChars: Array[Char]): Unit = {
    var gapCount = 0
    var i = idx
    while (i < vote.length && gapCount < newChars.length && vote(i).maxBy(_._2)._2 == vote(i)('-')) {
      vote(i)(newChars(gapCount)) += 1
      i += 1
      gapCount += 1
    }
    if (gapCount < newChars.length) insert(i, newChars.slice(gapCount, newChars.length))
    for (j <- 0 until newChars.length - gapCount)
      vote(i + j) += ('-' -> (vote(idx - 1).values.sum - 1))
  }
}

object ConsensusAlignment {

  val K = Settings.K
  val NWaligner = new NeedlemanWunschAligner(Settings.MAX_READ_LEN * 2, Settings.MAX_INDEL)

  def alignOneEnd(groupSeq: String, readSeq: String, readST: SuffixTree): (Int, Int, Array[Int]) = {
    // return mapping array like: -2 -2 0 1 2 -1 3 4 6 -3 -3 -3
    def sharp(a: (Int, Int, Int), b: (Int, Int, Int)): (Int, Int, Int) = { // remove overlap part from a
      val (a1, a2, a3, a4) = (a._1, a._1 + a._3, a._2, a._2 + a._3)
      val (b1, b2, b3, b4) = (b._1, b._1 + b._3, b._2, b._2 + b._3)
      if (Math.abs(a1 - b1 - a3 + b3) > Settings.MAX_INDEL) return null
      if (a1 < b1 && a2 <= b2 && a3 < b3 && a4 <= b4)
        return (a1, a3, a._3 - Math.max(Math.max(a2 - b1, a4 - b3), 0))
      if (b1 <= a1 && b2 < a2 && b3 <= a3 && b4 < a4) {
        val ovlp = Math.max(Math.max(b2 - a1, b4 - a3), 0)
        return (a1 + ovlp, a3 + ovlp, a._3 - ovlp)
      }
      null
    }

    def compactLength(a: (Int, Int, Int)): Int = {
      var i = a._2
      var j = a._2 + a._3 - 1
      while (i < a._2 + a._3 - 1 && groupSeq(i) == groupSeq(i + 1)) i += 1
      while (j > a._2 && groupSeq(j) == groupSeq(j - 1)) j -= 1
      Math.max(j - i + 1, 1)
    }

    val mapping = Array.fill[Int](groupSeq.length)(-1)
    val lengthThreshold = Math.log(readSeq.length) / Math.log(2)
    //    println(readST.pairwiseLCS(groupSeq))
    val LCS = readST.pairwiseLCS(groupSeq).filter(_._3 > K).filter(compactLength(_) > 2).sortBy(-_._3)
    //
    //        println(groupSeq)
    //        println(LCS)
    var matchSeg = List[(Int, Int, Int)]()
    for (m <- LCS) {
      var sharped = m
      for (nm <- matchSeg)
        if (sharped != null) sharped = sharp(sharped, nm)
      if (sharped != null) matchSeg = sharped :: matchSeg
    }
    matchSeg = matchSeg.sortBy(_._2)
    if (matchSeg.isEmpty) return (0, 0, null)
    var matchCount = 0
    var spanCount = 0
    val unmatchedReadHead = Math.min(matchSeg.head._1, matchSeg.head._2 + 2)
    val unmatchedGroupHead = Math.min(matchSeg.head._2, matchSeg.head._1 + 2)
    var lastR = matchSeg.head._1
    var lastG = matchSeg.head._2
    val HeadMapping = NWaligner.align(groupSeq.substring(lastG - unmatchedGroupHead, lastG).reverse,
      readSeq.substring(lastR - unmatchedReadHead, lastR).reverse, tailFlex = true)
    for (i <- HeadMapping.indices)
      mapping(lastG - 1 - i) = if (HeadMapping(i) == -1) -1 else lastR - 1 - HeadMapping(i)
    for (i <- lastG - unmatchedGroupHead until lastG if mapping(i) != -1 && groupSeq(i) == readSeq(mapping(i))) matchCount += 1
    spanCount += Math.min(unmatchedReadHead, unmatchedGroupHead)
    for ((rs, gs, len) <- matchSeg) {
      if (gs - lastG == rs - lastR) {
        for (i <- 0 until (gs - lastG)) mapping(lastG + i) = lastR + i
      } else {
        val regionMapping = NWaligner.align(groupSeq.substring(lastG, gs), readSeq.substring(lastR, rs))
        for (i <- regionMapping.indices)
          mapping(lastG + i) = if (regionMapping(i) == -1) -1 else lastR + regionMapping(i)
      }
      for (i <- lastG until gs if mapping(i) != -1 && groupSeq(i) == readSeq(mapping(i))) matchCount += 1
      matchCount += len
      spanCount += Math.max(gs - lastG, rs - lastR) + len
      for (i <- 0 until len) mapping(gs + i) = rs + i
      lastG = gs + len
      lastR = rs + len
    }
    val unmatchedReadTail = Math.min(readSeq.length - lastR, groupSeq.length - lastG + 2)
    val unmatchedGroupTail = Math.min(groupSeq.length - lastG, readSeq.length - lastR + 2)
    val tailMapping = NWaligner.align(groupSeq.substring(lastG, lastG + unmatchedGroupTail),
      readSeq.substring(lastR, lastR + unmatchedReadTail), tailFlex = true)
    for (i <- tailMapping.indices)
      mapping(lastG + i) = if (tailMapping(i) == -1) -1 else lastR + tailMapping(i)
    for (i <- lastG until lastG + unmatchedGroupTail if mapping(i) != -1 && groupSeq(i) == readSeq(mapping(i))) matchCount += 1
    spanCount += Math.min(unmatchedReadTail, unmatchedGroupTail)
    var i = 0
    while (mapping(i) == -1) {
      mapping(i) = -2
      i += 1
    }
    i = mapping.length - 1
    while (mapping(i) == -1) {
      mapping(i) = -3
      i -= 1
    }
    //    for (i <- mapping) printf("%d,",i)
    //    println()
    (matchCount, spanCount, mapping)
  }

  def test1(): Unit = {
    val read1 = new MappingRead(1, 1, "TTATCCTTTGAATGGTCGCCATGATGGTGGTTATTATACCGTCAAGGACTGTGTGACTATTGACGTCCTTCCCCGTACGCCGGGCAATAACGTTTATGTT",
      "GCCAGCCTGCAACGTACCTTCAAGAAGTCCTTTACCAGCTTTAGCCATAGCACCAGAAACAAAACTAGGGGCGGCCTCATCAGGGTTAGGAACATTAGAG")
    val read2 = new MappingRead(2, 1, "ACCGTCAAGGACTGTGTGACTATTGACGTCCTTCCCCGTACGCCGGGCAATAACGTTTATGTTGGTTTCATGGTTTGGTCTAACTTTACCGCTACTAAAT",
      "TATCAGCGGCAGACTTGCCACCAAGTCCAACCAAATGAAGCAACTTATCAGAAACGGCAGAAGTGCCAGACTGCAACGTACCTTCAAGAAGTCCTTTACC")
    val read3 = new MappingRead(3, 1, "GACGTCCTTCCCCGTACGCCGGGCAATAACGTTTATGTTGGTTTCATGGTTTGGTCTAACTTTACCGCTACTAAATGCCGCGGATTGGTTTCGCTGAATC",
      "GTATCCTTTCCTTTATCAGCGGCAGACTTGCCACCAAGTCCAACCAAATCAAGCAACTTATCAGAAACGGCAGAAGTGCCAGCCTGCAACGTACATTCAA")
    val read4 = new MappingRead(4, 1, "CCCCGTACGCCGGGCAATAACGTTTATGTTGGTTTCATGGTTTGGTCTAACTTTACCGCTACTAAATGCCGCGGATTGGTTTCCTGAATCAGGTTATTAA",
      "ATGCAGCAGCAAGATAATCACGAGTATCCTTTCCTTTATCAGCGGCAGACTTGCCACCAAGTCCAACCAAATCAAGCAACTTATCAGAAACGGCAGAAGT")
    val read5 = new MappingRead(5, 1, "CGTACGCCGGGCAATAACGTTTATGTTGGTTTCATGGTTTGGTCTAACTTTACCGCTACTAAATGCCGCGGATTGGATTCGCTGAATCAGGTTATTTAAG",
      "CAAGATAATCACGAGTATCCTTTCCTTTATCAGCGGCAGACTTGCCACCAAGTCCAACCAAATCAAGCAACTTATCAGAAACGGCAGAAGTGCCAGCCTG")
    val read6 = new MappingRead(6, 1, "GGGCAATAACGTTTATGTTGGTTTCATGGTTTGGTCTAACTTTACCGCTACTAAATGCCGCGGATTGGTTTCGCTGAATCAGGTTATTAAAGAGATTATT",
      "GCAGCAAGATAATCACGAGTATCCTTTCCTTTATAAGCGGCAGACTTGCCACCAAGTCCAACCAAATCAAGCTACTTATCAGAAACGGCAGAAGTGCCAG")
    val ca = new ConsensusAlignment(read1)
    var r: (Int, Array[Int], Array[Int]) = null
    r = ca.align(read2, new SuffixTree(read2.seq1), new SuffixTree(read2.seq2))
    ca.joinAndUpdate(read2, r._2, r._3)
    r = ca.align(read3, new SuffixTree(read3.seq1), new SuffixTree(read3.seq2))
    ca.joinAndUpdate(read3, r._2, r._3)
    r = ca.align(read4, new SuffixTree(read4.seq1), new SuffixTree(read4.seq2))
    ca.joinAndUpdate(read4, r._2, r._3)
    r = ca.align(read5, new SuffixTree(read5.seq1), new SuffixTree(read5.seq2))
    ca.joinAndUpdate(read5, r._2, r._3)
    r = ca.align(read6, new SuffixTree(read6.seq1), new SuffixTree(read6.seq2))
    ca.joinAndUpdate(read6, r._2, r._3)
    println(ca.printPileup())
    val result = ArrayBuffer[List[Long]]()
    ca.reportAllEdgesTuple("GAGCGGTCAGTAGC", result)
    println(result)
  }

  def test2(): Unit = {
    val read1 = new MappingRead(1, 1, "CATCGACGCTGTCCGGCGATCAACAATCTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACT",
      "AACTTTCCGAAGGCCAGAAACGTTGTGAGGAGATCAACCGTCAGAATCGTCAGTTGCGGGTGGAAATAATTCTGAATCGCTCTGGCATCCAGCCATTGCA")
    val read2 = new MappingRead(2, 1, "GCTGTCCGGCGATCAACAATCTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACTTCGCACA",
      "ATTCAAGACGGTAGCGGAGTGGCGCGAGTGGCAACTTTCCGAAGGCCAGAAACGTTGTGAGAAGATCAACCGTCAGAATCGTCAGTTGCGGGTGGTAAAA")
    val read3 = new MappingRead(3, 1, "TGTCCGGCGATCAACAATCTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACTTCGCACAGT",
      "GCCATTAAAGACGGTAGCGGAGTGGCGCGAGTGGCAACATTCCGAAGGGCAGAAACGTTGTGAGGAGATCAACCGTCAGAATCGTCAGTTGCGGGGGGAA")
    val read4 = new MappingRead(4, 1, "TCCGGCGATCAACAATCTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACTTCGCACAGTTC",
      "AGACGGTAGCGGAGTGGCGCGAGTGGCAACTTTCCGAAGGCCAGAAACGTTGTTAGGAGATCAACCGTCAGAATCGTCAGTTGCGGGTGGTAAAAATTCT")
    val read5 = new MappingRead(5, 1, "CGATCAACAATCTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACTTCGCACAGTTCCCGCA",
      "GTAGCGGAGTGGCGCGAGTGGCAACTTTCCGAAGGCCAGAAACGTTGTGAGGAGATCAACCGTCAGAATCGTCAGTTGCGGGTGGAAAAAATTCTGAATC")
    val read6 = new MappingRead(6, 1, "CTGGTGCAGTACCACCTGCTCGTTTTTCGTCTCGCGCTGAATGCCAATTTCATCAAGAACCAGCAGATCCACTTCGCACAGTTCCCGCAAAAATTTTTCG",
      "GCGGAGTGGCGCGAGTGGCAACTTTCCGAAGGCCAGAAACGTTGTGAGGAGATCAACCGTCAGAATCGTCAGTTGCGGGTGGAAAAATTTCTGACTCGCT")
    val ca = new ConsensusAlignment(read1)
    var r: (Int, Array[Int], Array[Int]) = null
    r = ca.align(read2, new SuffixTree(read2.seq1), new SuffixTree(read2.seq2))
    ca.joinAndUpdate(read2, r._2, r._3)
    r = ca.align(read3, new SuffixTree(read3.seq1), new SuffixTree(read3.seq2))
    ca.joinAndUpdate(read3, r._2, r._3)
    r = ca.align(read4, new SuffixTree(read4.seq1), new SuffixTree(read4.seq2))
    ca.joinAndUpdate(read4, r._2, r._3)
    r = ca.align(read5, new SuffixTree(read5.seq1), new SuffixTree(read5.seq2))
    ca.joinAndUpdate(read5, r._2, r._3)
    r = ca.align(read6, new SuffixTree(read6.seq1), new SuffixTree(read6.seq2))
    ca.joinAndUpdate(read6, r._2, r._3)
    println(ca.printPileup())
    val result = ArrayBuffer[List[Long]]()
    ca.reportAllEdgesTuple("GAGCGGTCAGTAGC", result)
    println(result)
  }

  def test3(): Unit = {
    val read1 = new MappingRead(1, 1, "TAGCCTTGGAGGATGGTCCCCCCATATTCAGACAGGATACCACGTGTCCCGCCCTACTCATCGAGCTCACAGCATGTCCATTTTTGTGTACGGGGCTGTC",
      "CTGAACAATGAAAGTTGTTCGTGAGTCTCTCAAATTTTCGCAACACGATGATGGATCGCAAGAAACATCTTCGGGTTGTGAGGTTAAGCGACTAAGCGTA")
    val read2 = new MappingRead(2, 1, "TAGCCTTGGAGGATGGTCCCCCCATATTCAGACAGGATACCACGTGTCCCGCCCTACTCATCGAGCTCACAGCATGTGCATATTTGTGTACGGGGCTGTC",
      "GTTCGTGAGTCTCTCAAATTTTCGCAACACGATGATGGATCGCAAGAAACATCTTCGGGTTGTGAGGTTAAGCGACTAAGCGTACACGGTGGATGCCCTG")
    val read3 = new MappingRead(2, 1, "AGCCTTGGAGGATGGTCCCCCCATATTCAGACAGGATACCACGTGTCCCGCCCTACTCATCGAGCTCACAGCATGTGCATTTTTGTGTACGGGGCTGTCA",
      "GAAACACTGAACAACGAAAGTTGTTCGTGAGTCTCTCAAATTTTCGCAACTCTGAAGTGAAACATCTTCGGGTTGTGAGGTTAAGCGACTAAGCGTACAC")
    val ca = new ConsensusAlignment(read1)
    var r: (Int, Array[Int], Array[Int]) = null
    r = ca.align(read2, new SuffixTree(read2.seq1), new SuffixTree(read2.seq2))
    ca.joinAndUpdate(read2, r._2, r._3)
    r = ca.align(read3, new SuffixTree(read3.seq1), new SuffixTree(read3.seq2))
    ca.joinAndUpdate(read3, r._2, r._3)
    for (i <- r._2) print(i + ",")
    println()
    println(ca.printPileup())
  }

  def testFromFile(): Unit = {
    val readArray = ArrayBuffer[MappingRead]()
    var n = 0
    var ca: ConsensusAlignment = null
    var r: (Int, Array[Int], Array[Int]) = null
    for (line <- Source.fromFile("Demo.txt").getLines) {
      val read = line.split(" ")
      n += 1
      readArray += new MappingRead(n, 1, read(0), read(1))
      if (n == 1) {
        ca = new ConsensusAlignment(readArray(0))
      } else {
        r = ca.align(readArray.last, new SuffixTree(readArray.last.seq1), new SuffixTree(readArray.last.seq2))
        ca.joinAndUpdate(readArray.last, r._2, r._3)
        println(ca.printPileup())
      }
    }
    println(ca.printPileup())
    val result = ArrayBuffer[List[Long]]()
    ca.reportAllEdgesTuple("GAGCGGTCAGTAGC", result)
    println(result)
  }

  def main(args: Array[String]): Unit = {
    testFromFile()
  }
}

class ConsensusAlignment(read: MappingRead) extends ArrayBuffer[MappingRead]() {
  var consensus0 = if (read.complemented) new ConsensusSequence(read.seq2) else null
  var consensus1 = new ConsensusSequence(read.seq1)
  var consensus2 = if (!read.complemented) new ConsensusSequence(read.seq2) else null
  this += read
  read.column1 = consensus1.columnID.toArray
  read.column2 = (if (read.complemented) consensus0 else consensus2).columnID.toArray

  def updateConsensus(consensus: ConsensusSequence, mapping: Array[Int], seq: String, readColumn: Array[Int]) {
    var inserted = 0
    if (mapping(0) > 0) {
      consensus.insert(0, seq.substring(0, mapping(0)).toCharArray)
      for (i <- 0 until mapping(0)) readColumn(i) = consensus.columnID(i)
      inserted += mapping(0)
    }
    var last = -1
    for (i <- mapping.indices) {
      if (mapping(i) > -1) {
        if (last != -1 && last + 1 != mapping(i)) {
          consensus.checkGap(inserted + i, seq.substring(last + 1, mapping(i)).toCharArray)
          for (j <- last + 1 until mapping(i)) readColumn(j) = consensus.columnID(inserted + i + j - last - 1)
          inserted += mapping(i) - last - 1
        }
        while (consensus.charAt(inserted + i) == '-') {
          consensus.addCount(inserted + i, '-')
          inserted += 1
        }
        consensus.addCount(inserted + i, seq(mapping(i)))
        readColumn(mapping(i)) = consensus.columnID(inserted + i)
        last = mapping(i)
      } else if (mapping(i) == -1) {
        while (consensus.charAt(inserted + i) == '-') {
          consensus.addCount(inserted + i, '-')
          inserted += 1
        }
        consensus.addCount(inserted + i, '-')
      } else {
        while (consensus.charAt(inserted + i) == '-') inserted += 1
      }
    }
    if (last + 1 != seq.length) {
      consensus.insert(inserted + mapping.length, seq.substring(last + 1).toCharArray)
      for (i <- last + 1 until seq.length) readColumn(i) = consensus.columnID(inserted + mapping.length + i - last - 1)
      inserted += mapping(0)
    }
    if (readColumn.contains(-1)) {
      println("Exception at Line 365!")
      for (i <- this) println(i.seq1 + " " + i.seq2)
      println(consensus.sequence())
      for (k <- consensus.columnID) print(k + ",")
      println()
      for (k <- mapping) print(k + ",")
      println()
      for (k <- readColumn) print(k + ",")
      println()
      println(seq)
    }
  }

  def joinAndUpdate(read: MappingRead, mapping1: Array[Int], mapping2: Array[Int]) {
    this += read
    val consensus_ = if (read.complemented) consensus0 else consensus2
    updateConsensus(consensus1, mapping1, read.seq1, read.column1)
    if (consensus_ == null){
      if (read.complemented){
        consensus0 = new ConsensusSequence(read.seq2)
        read.column2 = consensus0.columnID.toArray
      } else {
        consensus2 = new ConsensusSequence(read.seq2)
        read.column2 = consensus2.columnID.toArray
      }
    } else
    updateConsensus(consensus_, mapping2, read.seq2, read.column2)
  }

  def align(read: MappingRead, readST1: SuffixTree, readST2: SuffixTree): (Int, Array[Int], Array[Int]) = {
    val consensus_ = if (read.complemented) consensus0 else consensus2
    val (count1, span1, mapping1) = ConsensusAlignment.alignOneEnd(consensus1.sequence(), read.seq1, readST1)
    if (mapping1 == null || count1 < span1 * Settings.MATCH_RATE) return null
    if (consensus_ == null) return (2 * count1 - span1, mapping1, null)
    val (count2, span2, mapping2) = ConsensusAlignment.alignOneEnd(consensus_.sequence(), read.seq2, readST2)
    if (mapping2 == null || count2 < span2 * Settings.MATCH_RATE) return null
    (2 * count1 - span1 + 2 * count2 - span2, mapping1, mapping2)
  }

  def reportAllEdgesTuple(kmer: String, report: ArrayBuffer[List[Long]]): Unit = {
    def baseEncode(fileno:Int,position:Long,gapCount:Int,baseCode:Int): Long ={
      // outdated:48-bit identifier(base offset in input file) | 8 bit alignment gap offset | 3 bit base code
      // tried bitwise operation, it gathers hashCode of records and skew the partition
      var id = gapCount * 1000000000000L + position
      (id*6+baseCode)*2+fileno
    }
    val seq1KmerSet = new Array[mutable.Set[Int]](this.size)
    val minCommonKmer = Array.ofDim[Boolean](this.size, this.size)
    val thishash = kmer.hashCode
    for (i <- this.indices) {
      val seq1 = this(i).seq1
      seq1KmerSet(i) = mutable.Set[Int]()
      for (j <- 0 to seq1.length - ConsensusAlignment.K) {
        val kmerhash = Util.minKmerByRC(seq1.substring(j, j + ConsensusAlignment.K)).hashCode
        if (kmerhash <= thishash) seq1KmerSet(i) += kmerhash
      }
      for (j <- 0 until i)
        minCommonKmer(i)(j) = (seq1KmerSet(i) intersect seq1KmerSet(j)).min == thishash
    }
    val table = mutable.Map[Char, Int]('A' -> 0, 'C' -> 1, 'G' -> 2, 'T' -> 3, 'N' -> 4, '-' -> 5)
    val columns0 = if (consensus0!=null) Array.fill[ArrayBuffer[(Int, Long)]](consensus0.columnID.size)(ArrayBuffer[(Int, Long)]()) else null
    val columns1 = Array.fill[ArrayBuffer[(Int, Long)]](consensus1.columnID.size)(ArrayBuffer[(Int, Long)]())
    val columns2 = if (consensus2!=null) Array.fill[ArrayBuffer[(Int, Long)]](consensus2.columnID.size)(ArrayBuffer[(Int, Long)]()) else null
    for (idx <- this.indices) {
      val read = this (idx)
      // anchored mate
      var refidx = 0
      var first = true
      for (i <- read.column1.indices) {
        var gapCount = 0
        while (consensus1.columnID(refidx) != read.column1(i)) {
          if (!first) {
            val baseCode = if (read.complemented)
              baseEncode(if (read.reversed) 1 else 0, read.id + read.seq1.length-1-i, gapCount, table('-'))
            else
              baseEncode(if (read.reversed) 1 else 0, read.id + i, gapCount, table('-'))
            columns1(refidx) += ((idx, baseCode))
          }
          refidx += 1
          gapCount += 1
        }
        val baseCode = if (read.complemented)
          baseEncode(if (read.reversed) 1 else 0, read.id + read.seq1.length-1-i, 0, table(Util.complement(read.seq1(i))))
        else
          baseEncode(if (read.reversed) 1 else 0, read.id + i, 0, table(read.seq1(i)))
        columns1(refidx) += ((idx, baseCode))
        refidx += 1
        first = false
      }
      // the other mate
      val consensus_ = if (read.complemented) consensus0 else consensus2
      val columns_ = if (read.complemented) columns0 else columns2
      refidx = 0
      first = true
      for (i <- read.column2.indices) {
        var gapCount = 0
        while (consensus_.columnID(refidx) != read.column2(i)) {
          if (!first) {
            val baseCode = baseEncode(if (!read.reversed) 1 else 0, read.id + i, gapCount,table('-'))
            columns_(refidx) += ((idx, baseCode))
          }
          refidx += 1
          gapCount += 1
        }
        val baseCode = baseEncode(if (!read.reversed) 1 else 0, read.id + i, 0,table(read.seq2(i)))
        columns_(refidx) += ((idx, baseCode))
        refidx += 1
        first = false
      }
    }
    if (columns0!=null)
    for (column <- columns0) {
      var edge = List[Long]()
      var prev = List[Int]()
      for (j <- column) {
        var allCommonMin = true
        for (k <- prev) allCommonMin &= minCommonKmer(j._1)(k)
        if (allCommonMin) edge ::= j._2
        prev ::= j._1
      }
      if (edge.size > 1) report += edge
    }
    for (column <- columns1) {
      var edge = List[Long]()
      var prev = List[Int]()
      for (j <- column) {
        var allCommonMin = true
        for (k <- prev) allCommonMin &= minCommonKmer(j._1)(k)
        if (allCommonMin) edge ::= j._2 * (if (this(j._1).complemented) -1 else 1)
        prev ::= j._1
      }
      if (edge.size > 1) report += edge
    }
    if (columns2!=null)
    for (column <- columns2) {
      var edge = List[Long]()
      var prev = List[Int]()
      for (j <- column) {
        var allCommonMin = true
        for (k <- prev) allCommonMin &= minCommonKmer(j._1)(k)
        if (allCommonMin) edge ::= j._2
        prev ::= j._1
      }
      if (edge.size > 1) report += edge
    }
  }

  def printPileup(): String = {
    var pileup = f"${"Consensus"}%20s " +
      (if (consensus0!=null) consensus0.sequence(true) else "") + "|" +
      consensus1.sequence(true) + "|" +
      (if (consensus2!=null) consensus2.sequence(true) else "") + "\n"
    for (read <- this) {
      pileup += f"${read.id}%20s "
      var refidx = 0
      if (read.complemented)
      for (i <- read.column2.indices) {
        while (consensus0.columnID(refidx) != read.column2(i)) {
          pileup += " "
          refidx += 1
        }
        pileup += read.seq2(i)
        refidx += 1
      }
      pileup += " " * (if (consensus0!=null) consensus0.columnID.length - refidx else 0) + "|"
      refidx = 0
      for (i <- read.column1.indices) {
        while (consensus1.columnID(refidx) != read.column1(i)) {
          pileup += " "
          refidx += 1
        }
        pileup += read.seq1(i)
        refidx += 1
      }
      pileup += " " * (consensus1.columnID.length - refidx) + "|"
      refidx = 0
      if (!read.complemented)
      for (i <- read.column2.indices) {
        while (consensus2.columnID(refidx) != read.column2(i)) {
          pileup += " "
          refidx += 1
        }
        pileup += read.seq2(i)
        refidx += 1
      }
      pileup += "\n"
    }
    pileup + "-----\n"
  }
}