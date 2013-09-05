/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin.makedict;

import com.android.inputmethod.latin.makedict.BinaryDictDecoderUtils.CharEncoding;
import com.android.inputmethod.latin.makedict.FormatSpec.FormatOptions;
import com.android.inputmethod.latin.makedict.FusionDictionary.PtNode;
import com.android.inputmethod.latin.makedict.FusionDictionary.DictionaryOptions;
import com.android.inputmethod.latin.makedict.FusionDictionary.PtNodeArray;
import com.android.inputmethod.latin.makedict.FusionDictionary.WeightedString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Encodes binary files for a FusionDictionary.
 *
 * All the methods in this class are static.
 *
 * TODO: Rename this class to DictEncoderUtils.
 */
public class BinaryDictEncoderUtils {

    private static final boolean DBG = MakedictLog.DBG;

    private BinaryDictEncoderUtils() {
        // This utility class is not publicly instantiable.
    }

    // Arbitrary limit to how much passes we consider address size compression should
    // terminate in. At the time of this writing, our largest dictionary completes
    // compression in five passes.
    // If the number of passes exceeds this number, makedict bails with an exception on
    // suspicion that a bug might be causing an infinite loop.
    private static final int MAX_PASSES = 24;

    /**
     * Compute the binary size of the character array.
     *
     * If only one character, this is the size of this character. If many, it's the sum of their
     * sizes + 1 byte for the terminator.
     *
     * @param characters the character array
     * @return the size of the char array, including the terminator if any
     */
    static int getPtNodeCharactersSize(final int[] characters) {
        int size = CharEncoding.getCharArraySize(characters);
        if (characters.length > 1) size += FormatSpec.PTNODE_TERMINATOR_SIZE;
        return size;
    }

    /**
     * Compute the binary size of the character array in a PtNode
     *
     * If only one character, this is the size of this character. If many, it's the sum of their
     * sizes + 1 byte for the terminator.
     *
     * @param ptNode the PtNode
     * @return the size of the char array, including the terminator if any
     */
    private static int getPtNodeCharactersSize(final PtNode ptNode) {
        return getPtNodeCharactersSize(ptNode.mChars);
    }

    /**
     * Compute the binary size of the PtNode count for a node array.
     * @param nodeArray the nodeArray
     * @return the size of the PtNode count, either 1 or 2 bytes.
     */
    private static int getPtNodeCountSize(final PtNodeArray nodeArray) {
        return BinaryDictIOUtils.getPtNodeCountSize(nodeArray.mData.size());
    }

    /**
     * Compute the size of a shortcut in bytes.
     */
    private static int getShortcutSize(final WeightedString shortcut) {
        int size = FormatSpec.PTNODE_ATTRIBUTE_FLAGS_SIZE;
        final String word = shortcut.mWord;
        final int length = word.length();
        for (int i = 0; i < length; i = word.offsetByCodePoints(i, 1)) {
            final int codePoint = word.codePointAt(i);
            size += CharEncoding.getCharSize(codePoint);
        }
        size += FormatSpec.PTNODE_TERMINATOR_SIZE;
        return size;
    }

    /**
     * Compute the size of a shortcut list in bytes.
     *
     * This is known in advance and does not change according to position in the file
     * like address lists do.
     */
    static int getShortcutListSize(final ArrayList<WeightedString> shortcutList) {
        if (null == shortcutList || shortcutList.isEmpty()) return 0;
        int size = FormatSpec.PTNODE_SHORTCUT_LIST_SIZE_SIZE;
        for (final WeightedString shortcut : shortcutList) {
            size += getShortcutSize(shortcut);
        }
        return size;
    }

    /**
     * Compute the maximum size of a PtNode, assuming 3-byte addresses for everything.
     *
     * @param ptNode the PtNode to compute the size of.
     * @param options file format options.
     * @return the maximum size of the PtNode.
     */
    private static int getPtNodeMaximumSize(final PtNode ptNode, final FormatOptions options) {
        int size = getNodeHeaderSize(ptNode, options);
        // If terminal, one byte for the frequency
        if (ptNode.isTerminal()) size += FormatSpec.PTNODE_FREQUENCY_SIZE;
        size += FormatSpec.PTNODE_MAX_ADDRESS_SIZE; // For children address
        size += getShortcutListSize(ptNode.mShortcutTargets);
        if (null != ptNode.mBigrams) {
            size += (FormatSpec.PTNODE_ATTRIBUTE_FLAGS_SIZE
                    + FormatSpec.PTNODE_ATTRIBUTE_MAX_ADDRESS_SIZE)
                    * ptNode.mBigrams.size();
        }
        return size;
    }

    /**
     * Compute the maximum size of each PtNode of a PtNode array, assuming 3-byte addresses for
     * everything, and caches it in the `mCachedSize' member of the nodes; deduce the size of
     * the containing node array, and cache it it its 'mCachedSize' member.
     *
     * @param ptNodeArray the node array to compute the maximum size of.
     * @param options file format options.
     */
    private static void calculatePtNodeArrayMaximumSize(final PtNodeArray ptNodeArray,
            final FormatOptions options) {
        int size = getPtNodeCountSize(ptNodeArray);
        for (PtNode node : ptNodeArray.mData) {
            final int nodeSize = getPtNodeMaximumSize(node, options);
            node.mCachedSize = nodeSize;
            size += nodeSize;
        }
        if (options.mSupportsDynamicUpdate) {
            size += FormatSpec.FORWARD_LINK_ADDRESS_SIZE;
        }
        ptNodeArray.mCachedSize = size;
    }

    /**
     * Compute the size of the header (flag + [parent address] + characters size) of a PtNode.
     *
     * @param ptNode the PtNode of which to compute the size of the header
     * @param options file format options.
     */
    private static int getNodeHeaderSize(final PtNode ptNode, final FormatOptions options) {
        if (BinaryDictIOUtils.supportsDynamicUpdate(options)) {
            return FormatSpec.PTNODE_FLAGS_SIZE + FormatSpec.PARENT_ADDRESS_SIZE
                    + getPtNodeCharactersSize(ptNode);
        } else {
            return FormatSpec.PTNODE_FLAGS_SIZE + getPtNodeCharactersSize(ptNode);
        }
    }

    /**
     * Compute the size, in bytes, that an address will occupy.
     *
     * This can be used either for children addresses (which are always positive) or for
     * attribute, which may be positive or negative but
     * store their sign bit separately.
     *
     * @param address the address
     * @return the byte size.
     */
    static int getByteSize(final int address) {
        assert(address <= FormatSpec.UINT24_MAX);
        if (!BinaryDictIOUtils.hasChildrenAddress(address)) {
            return 0;
        } else if (Math.abs(address) <= FormatSpec.UINT8_MAX) {
            return 1;
        } else if (Math.abs(address) <= FormatSpec.UINT16_MAX) {
            return 2;
        } else {
            return 3;
        }
    }

    // End utility methods

    // This method is responsible for finding a nice ordering of the nodes that favors run-time
    // cache performance and dictionary size.
    /* package for tests */ static ArrayList<PtNodeArray> flattenTree(
            final PtNodeArray rootNodeArray) {
        final int treeSize = FusionDictionary.countPtNodes(rootNodeArray);
        MakedictLog.i("Counted nodes : " + treeSize);
        final ArrayList<PtNodeArray> flatTree = new ArrayList<PtNodeArray>(treeSize);
        return flattenTreeInner(flatTree, rootNodeArray);
    }

    private static ArrayList<PtNodeArray> flattenTreeInner(final ArrayList<PtNodeArray> list,
            final PtNodeArray ptNodeArray) {
        // Removing the node is necessary if the tails are merged, because we would then
        // add the same node several times when we only want it once. A number of places in
        // the code also depends on any node being only once in the list.
        // Merging tails can only be done if there are no attributes. Searching for attributes
        // in LatinIME code depends on a total breadth-first ordering, which merging tails
        // breaks. If there are no attributes, it should be fine (and reduce the file size)
        // to merge tails, and removing the node from the list would be necessary. However,
        // we don't merge tails because breaking the breadth-first ordering would result in
        // extreme overhead at bigram lookup time (it would make the search function O(n) instead
        // of the current O(log(n)), where n=number of nodes in the dictionary which is pretty
        // high).
        // If no nodes are ever merged, we can't have the same node twice in the list, hence
        // searching for duplicates in unnecessary. It is also very performance consuming,
        // since `list' is an ArrayList so it's an O(n) operation that runs on all nodes, making
        // this simple list.remove operation O(n*n) overall. On Android this overhead is very
        // high.
        // For future reference, the code to remove duplicate is a simple : list.remove(node);
        list.add(ptNodeArray);
        final ArrayList<PtNode> branches = ptNodeArray.mData;
        final int nodeSize = branches.size();
        for (PtNode ptNode : branches) {
            if (null != ptNode.mChildren) flattenTreeInner(list, ptNode.mChildren);
        }
        return list;
    }

    /**
     * Get the offset from a position inside a current node array to a target node array, during
     * update.
     *
     * If the current node array is before the target node array, the target node array has not
     * been updated yet, so we should return the offset from the old position of the current node
     * array to the old position of the target node array. If on the other hand the target is
     * before the current node array, it already has been updated, so we should return the offset
     * from the new position in the current node array to the new position in the target node
     * array.
     *
     * @param currentNodeArray node array containing the PtNode where the offset will be written
     * @param offsetFromStartOfCurrentNodeArray offset, in bytes, from the start of currentNodeArray
     * @param targetNodeArray the target node array to get the offset to
     * @return the offset to the target node array
     */
    private static int getOffsetToTargetNodeArrayDuringUpdate(final PtNodeArray currentNodeArray,
            final int offsetFromStartOfCurrentNodeArray, final PtNodeArray targetNodeArray) {
        final boolean isTargetBeforeCurrent = (targetNodeArray.mCachedAddressBeforeUpdate
                < currentNodeArray.mCachedAddressBeforeUpdate);
        if (isTargetBeforeCurrent) {
            return targetNodeArray.mCachedAddressAfterUpdate
                    - (currentNodeArray.mCachedAddressAfterUpdate
                            + offsetFromStartOfCurrentNodeArray);
        } else {
            return targetNodeArray.mCachedAddressBeforeUpdate
                    - (currentNodeArray.mCachedAddressBeforeUpdate
                            + offsetFromStartOfCurrentNodeArray);
        }
    }

    /**
     * Get the offset from a position inside a current node array to a target PtNode, during
     * update.
     *
     * @param currentNodeArray node array containing the PtNode where the offset will be written
     * @param offsetFromStartOfCurrentNodeArray offset, in bytes, from the start of currentNodeArray
     * @param targetPtNode the target PtNode to get the offset to
     * @return the offset to the target PtNode
     */
    // TODO: is there any way to factorize this method with the one above?
    private static int getOffsetToTargetPtNodeDuringUpdate(final PtNodeArray currentNodeArray,
            final int offsetFromStartOfCurrentNodeArray, final PtNode targetPtNode) {
        final int oldOffsetBasePoint = currentNodeArray.mCachedAddressBeforeUpdate
                + offsetFromStartOfCurrentNodeArray;
        final boolean isTargetBeforeCurrent = (targetPtNode.mCachedAddressBeforeUpdate
                < oldOffsetBasePoint);
        // If the target is before the current node array, then its address has already been
        // updated. We can use the AfterUpdate member, and compare it to our own member after
        // update. Otherwise, the AfterUpdate member is not updated yet, so we need to use the
        // BeforeUpdate member, and of course we have to compare this to our own address before
        // update.
        if (isTargetBeforeCurrent) {
            final int newOffsetBasePoint = currentNodeArray.mCachedAddressAfterUpdate
                    + offsetFromStartOfCurrentNodeArray;
            return targetPtNode.mCachedAddressAfterUpdate - newOffsetBasePoint;
        } else {
            return targetPtNode.mCachedAddressBeforeUpdate - oldOffsetBasePoint;
        }
    }

    /**
     * Computes the actual node array size, based on the cached addresses of the children nodes.
     *
     * Each node array stores its tentative address. During dictionary address computing, these
     * are not final, but they can be used to compute the node array size (the node array size
     * depends on the address of the children because the number of bytes necessary to store an
     * address depends on its numeric value. The return value indicates whether the node array
     * contents (as in, any of the addresses stored in the cache fields) have changed with
     * respect to their previous value.
     *
     * @param ptNodeArray the node array to compute the size of.
     * @param dict the dictionary in which the word/attributes are to be found.
     * @param formatOptions file format options.
     * @return false if none of the cached addresses inside the node array changed, true otherwise.
     */
    private static boolean computeActualPtNodeArraySize(final PtNodeArray ptNodeArray,
            final FusionDictionary dict, final FormatOptions formatOptions) {
        boolean changed = false;
        int size = getPtNodeCountSize(ptNodeArray);
        for (PtNode ptNode : ptNodeArray.mData) {
            ptNode.mCachedAddressAfterUpdate = ptNodeArray.mCachedAddressAfterUpdate + size;
            if (ptNode.mCachedAddressAfterUpdate != ptNode.mCachedAddressBeforeUpdate) {
                changed = true;
            }
            int nodeSize = getNodeHeaderSize(ptNode, formatOptions);
            if (ptNode.isTerminal()) nodeSize += FormatSpec.PTNODE_FREQUENCY_SIZE;
            if (formatOptions.mSupportsDynamicUpdate) {
                nodeSize += FormatSpec.SIGNED_CHILDREN_ADDRESS_SIZE;
            } else if (null != ptNode.mChildren) {
                nodeSize += getByteSize(getOffsetToTargetNodeArrayDuringUpdate(ptNodeArray,
                        nodeSize + size, ptNode.mChildren));
            }
            nodeSize += getShortcutListSize(ptNode.mShortcutTargets);
            if (null != ptNode.mBigrams) {
                for (WeightedString bigram : ptNode.mBigrams) {
                    final int offset = getOffsetToTargetPtNodeDuringUpdate(ptNodeArray,
                            nodeSize + size + FormatSpec.PTNODE_FLAGS_SIZE,
                            FusionDictionary.findWordInTree(dict.mRootNodeArray, bigram.mWord));
                    nodeSize += getByteSize(offset) + FormatSpec.PTNODE_FLAGS_SIZE;
                }
            }
            ptNode.mCachedSize = nodeSize;
            size += nodeSize;
        }
        if (formatOptions.mSupportsDynamicUpdate) {
            size += FormatSpec.FORWARD_LINK_ADDRESS_SIZE;
        }
        if (ptNodeArray.mCachedSize != size) {
            ptNodeArray.mCachedSize = size;
            changed = true;
        }
        return changed;
    }

    /**
     * Initializes the cached addresses of node arrays and their containing nodes from their size.
     *
     * @param flatNodes the list of node arrays.
     * @param formatOptions file format options.
     * @return the byte size of the entire stack.
     */
    private static int initializePtNodeArraysCachedAddresses(final ArrayList<PtNodeArray> flatNodes,
            final FormatOptions formatOptions) {
        int nodeArrayOffset = 0;
        for (final PtNodeArray nodeArray : flatNodes) {
            nodeArray.mCachedAddressBeforeUpdate = nodeArrayOffset;
            int nodeCountSize = getPtNodeCountSize(nodeArray);
            int nodeffset = 0;
            for (final PtNode ptNode : nodeArray.mData) {
                ptNode.mCachedAddressBeforeUpdate = ptNode.mCachedAddressAfterUpdate =
                        nodeCountSize + nodeArrayOffset + nodeffset;
                nodeffset += ptNode.mCachedSize;
            }
            final int nodeSize = nodeCountSize + nodeffset
                    + (formatOptions.mSupportsDynamicUpdate
                            ? FormatSpec.FORWARD_LINK_ADDRESS_SIZE : 0);
            nodeArrayOffset += nodeArray.mCachedSize;
        }
        return nodeArrayOffset;
    }

    /**
     * Updates the cached addresses of node arrays after recomputing their new positions.
     *
     * @param flatNodes the list of node arrays.
     */
    private static void updatePtNodeArraysCachedAddresses(final ArrayList<PtNodeArray> flatNodes) {
        for (final PtNodeArray nodeArray : flatNodes) {
            nodeArray.mCachedAddressBeforeUpdate = nodeArray.mCachedAddressAfterUpdate;
            for (final PtNode ptNode : nodeArray.mData) {
                ptNode.mCachedAddressBeforeUpdate = ptNode.mCachedAddressAfterUpdate;
            }
        }
    }

    /**
     * Compute the cached parent addresses after all has been updated.
     *
     * The parent addresses are used by some binary formats at write-to-disk time. Not all formats
     * need them. In particular, version 2 does not need them, and version 3 does.
     *
     * @param flatNodes the flat array of node arrays to fill in
     */
    private static void computeParentAddresses(final ArrayList<PtNodeArray> flatNodes) {
        for (final PtNodeArray nodeArray : flatNodes) {
            for (final PtNode ptNode : nodeArray.mData) {
                if (null != ptNode.mChildren) {
                    // Assign my address to children's parent address
                    // Here BeforeUpdate and AfterUpdate addresses have the same value, so it
                    // does not matter which we use.
                    ptNode.mChildren.mCachedParentAddress = ptNode.mCachedAddressAfterUpdate
                            - ptNode.mChildren.mCachedAddressAfterUpdate;
                }
            }
        }
    }

    /**
     * Compute the addresses and sizes of an ordered list of PtNode arrays.
     *
     * This method takes a list of PtNode arrays and will update their cached address and size
     * values so that they can be written into a file. It determines the smallest size each of the
     * PtNode arrays can be given the addresses of its children and attributes, and store that into
     * each PtNode.
     * The order of the PtNode is given by the order of the array. This method makes no effort
     * to find a good order; it only mechanically computes the size this order results in.
     *
     * @param dict the dictionary
     * @param flatNodes the ordered list of PtNode arrays
     * @param formatOptions file format options.
     * @return the same array it was passed. The nodes have been updated for address and size.
     */
    private static ArrayList<PtNodeArray> computeAddresses(final FusionDictionary dict,
            final ArrayList<PtNodeArray> flatNodes, final FormatOptions formatOptions) {
        // First get the worst possible sizes and offsets
        for (final PtNodeArray n : flatNodes) calculatePtNodeArrayMaximumSize(n, formatOptions);
        final int offset = initializePtNodeArraysCachedAddresses(flatNodes, formatOptions);

        MakedictLog.i("Compressing the array addresses. Original size : " + offset);
        MakedictLog.i("(Recursively seen size : " + offset + ")");

        int passes = 0;
        boolean changesDone = false;
        do {
            changesDone = false;
            int ptNodeArrayStartOffset = 0;
            for (final PtNodeArray ptNodeArray : flatNodes) {
                ptNodeArray.mCachedAddressAfterUpdate = ptNodeArrayStartOffset;
                final int oldNodeArraySize = ptNodeArray.mCachedSize;
                final boolean changed =
                        computeActualPtNodeArraySize(ptNodeArray, dict, formatOptions);
                final int newNodeArraySize = ptNodeArray.mCachedSize;
                if (oldNodeArraySize < newNodeArraySize) {
                    throw new RuntimeException("Increased size ?!");
                }
                ptNodeArrayStartOffset += newNodeArraySize;
                changesDone |= changed;
            }
            updatePtNodeArraysCachedAddresses(flatNodes);
            ++passes;
            if (passes > MAX_PASSES) throw new RuntimeException("Too many passes - probably a bug");
        } while (changesDone);

        if (formatOptions.mSupportsDynamicUpdate) {
            computeParentAddresses(flatNodes);
        }
        final PtNodeArray lastPtNodeArray = flatNodes.get(flatNodes.size() - 1);
        MakedictLog.i("Compression complete in " + passes + " passes.");
        MakedictLog.i("After address compression : "
                + (lastPtNodeArray.mCachedAddressAfterUpdate + lastPtNodeArray.mCachedSize));

        return flatNodes;
    }

    /**
     * Sanity-checking method.
     *
     * This method checks a list of PtNode arrays for juxtaposition, that is, it will do
     * nothing if each node array's cached address is actually the previous node array's address
     * plus the previous node's size.
     * If this is not the case, it will throw an exception.
     *
     * @param arrays the list of node arrays to check
     */
    private static void checkFlatPtNodeArrayList(final ArrayList<PtNodeArray> arrays) {
        int offset = 0;
        int index = 0;
        for (final PtNodeArray ptNodeArray : arrays) {
            // BeforeUpdate and AfterUpdate addresses are the same here, so it does not matter
            // which we use.
            if (ptNodeArray.mCachedAddressAfterUpdate != offset) {
                throw new RuntimeException("Wrong address for node " + index
                        + " : expected " + offset + ", got " +
                        ptNodeArray.mCachedAddressAfterUpdate);
            }
            ++index;
            offset += ptNodeArray.mCachedSize;
        }
    }

    /**
     * Helper method to write a variable-size address to a file.
     *
     * @param buffer the buffer to write to.
     * @param index the index in the buffer to write the address to.
     * @param address the address to write.
     * @return the size in bytes the address actually took.
     */
    private static int writeVariableAddress(final byte[] buffer, int index, final int address) {
        switch (getByteSize(address)) {
        case 1:
            buffer[index++] = (byte)address;
            return 1;
        case 2:
            buffer[index++] = (byte)(0xFF & (address >> 8));
            buffer[index++] = (byte)(0xFF & address);
            return 2;
        case 3:
            buffer[index++] = (byte)(0xFF & (address >> 16));
            buffer[index++] = (byte)(0xFF & (address >> 8));
            buffer[index++] = (byte)(0xFF & address);
            return 3;
        case 0:
            return 0;
        default:
            throw new RuntimeException("Address " + address + " has a strange size");
        }
    }

    /**
     * Helper method to write a variable-size signed address to a file.
     *
     * @param buffer the buffer to write to.
     * @param index the index in the buffer to write the address to.
     * @param address the address to write.
     * @return the size in bytes the address actually took.
     */
    private static int writeVariableSignedAddress(final byte[] buffer, int index,
            final int address) {
        if (!BinaryDictIOUtils.hasChildrenAddress(address)) {
            buffer[index] = buffer[index + 1] = buffer[index + 2] = 0;
        } else {
            final int absAddress = Math.abs(address);
            buffer[index++] =
                    (byte)((address < 0 ? FormatSpec.MSB8 : 0) | (0xFF & (absAddress >> 16)));
            buffer[index++] = (byte)(0xFF & (absAddress >> 8));
            buffer[index++] = (byte)(0xFF & absAddress);
        }
        return 3;
    }

    /**
     * Makes the flag value for a PtNode.
     *
     * @param hasMultipleChars whether the PtNode has multiple chars.
     * @param isTerminal whether the PtNode is terminal.
     * @param childrenAddressSize the size of a children address.
     * @param hasShortcuts whether the PtNode has shortcuts.
     * @param hasBigrams whether the PtNode has bigrams.
     * @param isNotAWord whether the PtNode is not a word.
     * @param isBlackListEntry whether the PtNode is a blacklist entry.
     * @param formatOptions file format options.
     * @return the flags
     */
    static int makePtNodeFlags(final boolean hasMultipleChars, final boolean isTerminal,
            final int childrenAddressSize, final boolean hasShortcuts, final boolean hasBigrams,
            final boolean isNotAWord, final boolean isBlackListEntry,
            final FormatOptions formatOptions) {
        byte flags = 0;
        if (hasMultipleChars) flags |= FormatSpec.FLAG_HAS_MULTIPLE_CHARS;
        if (isTerminal) flags |= FormatSpec.FLAG_IS_TERMINAL;
        if (formatOptions.mSupportsDynamicUpdate) {
            flags |= FormatSpec.FLAG_IS_NOT_MOVED;
        } else if (true) {
            switch (childrenAddressSize) {
                case 1:
                    flags |= FormatSpec.FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE;
                    break;
                case 2:
                    flags |= FormatSpec.FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES;
                    break;
                case 3:
                    flags |= FormatSpec.FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES;
                    break;
                case 0:
                    flags |= FormatSpec.FLAG_CHILDREN_ADDRESS_TYPE_NOADDRESS;
                    break;
                default:
                    throw new RuntimeException("Node with a strange address");
            }
        }
        if (hasShortcuts) flags |= FormatSpec.FLAG_HAS_SHORTCUT_TARGETS;
        if (hasBigrams) flags |= FormatSpec.FLAG_HAS_BIGRAMS;
        if (isNotAWord) flags |= FormatSpec.FLAG_IS_NOT_A_WORD;
        if (isBlackListEntry) flags |= FormatSpec.FLAG_IS_BLACKLISTED;
        return flags;
    }

    private static byte makePtNodeFlags(final PtNode node, final int ptNodeAddress,
            final int childrenOffset, final FormatOptions formatOptions) {
        return (byte) makePtNodeFlags(node.mChars.length > 1, node.mFrequency >= 0,
                getByteSize(childrenOffset),
                node.mShortcutTargets != null && !node.mShortcutTargets.isEmpty(),
                node.mBigrams != null, node.mIsNotAWord, node.mIsBlacklistEntry, formatOptions);
    }

    /**
     * Makes the flag value for a bigram.
     *
     * @param more whether there are more bigrams after this one.
     * @param offset the offset of the bigram.
     * @param bigramFrequency the frequency of the bigram, 0..255.
     * @param unigramFrequency the unigram frequency of the same word, 0..255.
     * @param word the second bigram, for debugging purposes
     * @return the flags
     */
    private static final int makeBigramFlags(final boolean more, final int offset,
            int bigramFrequency, final int unigramFrequency, final String word) {
        int bigramFlags = (more ? FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT : 0)
                + (offset < 0 ? FormatSpec.FLAG_BIGRAM_ATTR_OFFSET_NEGATIVE : 0);
        switch (getByteSize(offset)) {
        case 1:
            bigramFlags |= FormatSpec.FLAG_BIGRAM_ATTR_ADDRESS_TYPE_ONEBYTE;
            break;
        case 2:
            bigramFlags |= FormatSpec.FLAG_BIGRAM_ATTR_ADDRESS_TYPE_TWOBYTES;
            break;
        case 3:
            bigramFlags |= FormatSpec.FLAG_BIGRAM_ATTR_ADDRESS_TYPE_THREEBYTES;
            break;
        default:
            throw new RuntimeException("Strange offset size");
        }
        if (unigramFrequency > bigramFrequency) {
            MakedictLog.e("Unigram freq is superior to bigram freq for \"" + word
                    + "\". Bigram freq is " + bigramFrequency + ", unigram freq for "
                    + word + " is " + unigramFrequency);
            bigramFrequency = unigramFrequency;
        }
        // We compute the difference between 255 (which means probability = 1) and the
        // unigram score. We split this into a number of discrete steps.
        // Now, the steps are numbered 0~15; 0 represents an increase of 1 step while 15
        // represents an increase of 16 steps: a value of 15 will be interpreted as the median
        // value of the 16th step. In all justice, if the bigram frequency is low enough to be
        // rounded below the first step (which means it is less than half a step higher than the
        // unigram frequency) then the unigram frequency itself is the best approximation of the
        // bigram freq that we could possibly supply, hence we should *not* include this bigram
        // in the file at all.
        // until this is done, we'll write 0 and slightly overestimate this case.
        // In other words, 0 means "between 0.5 step and 1.5 step", 1 means "between 1.5 step
        // and 2.5 steps", and 15 means "between 15.5 steps and 16.5 steps". So we want to
        // divide our range [unigramFreq..MAX_TERMINAL_FREQUENCY] in 16.5 steps to get the
        // step size. Then we compute the start of the first step (the one where value 0 starts)
        // by adding half-a-step to the unigramFrequency. From there, we compute the integer
        // number of steps to the bigramFrequency. One last thing: we want our steps to include
        // their lower bound and exclude their higher bound so we need to have the first step
        // start at exactly 1 unit higher than floor(unigramFreq + half a step).
        // Note : to reconstruct the score, the dictionary reader will need to divide
        // MAX_TERMINAL_FREQUENCY - unigramFreq by 16.5 likewise to get the value of the step,
        // and add (discretizedFrequency + 0.5 + 0.5) times this value to get the best
        // approximation. (0.5 to get the first step start, and 0.5 to get the middle of the
        // step pointed by the discretized frequency.
        final float stepSize =
                (FormatSpec.MAX_TERMINAL_FREQUENCY - unigramFrequency)
                / (1.5f + FormatSpec.MAX_BIGRAM_FREQUENCY);
        final float firstStepStart = 1 + unigramFrequency + (stepSize / 2.0f);
        final int discretizedFrequency = (int)((bigramFrequency - firstStepStart) / stepSize);
        // If the bigram freq is less than half-a-step higher than the unigram freq, we get -1
        // here. The best approximation would be the unigram freq itself, so we should not
        // include this bigram in the dictionary. For now, register as 0, and live with the
        // small over-estimation that we get in this case. TODO: actually remove this bigram
        // if discretizedFrequency < 0.
        final int finalBigramFrequency = discretizedFrequency > 0 ? discretizedFrequency : 0;
        bigramFlags += finalBigramFrequency & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY;
        return bigramFlags;
    }

    /**
     * Makes the 2-byte value for options flags.
     */
    private static final int makeOptionsValue(final FusionDictionary dictionary,
            final FormatOptions formatOptions) {
        final DictionaryOptions options = dictionary.mOptions;
        final boolean hasBigrams = dictionary.hasBigrams();
        return (options.mFrenchLigatureProcessing ? FormatSpec.FRENCH_LIGATURE_PROCESSING_FLAG : 0)
                + (options.mGermanUmlautProcessing ? FormatSpec.GERMAN_UMLAUT_PROCESSING_FLAG : 0)
                + (hasBigrams ? FormatSpec.CONTAINS_BIGRAMS_FLAG : 0)
                + (formatOptions.mSupportsDynamicUpdate ? FormatSpec.SUPPORTS_DYNAMIC_UPDATE : 0);
    }

    /**
     * Makes the flag value for a shortcut.
     *
     * @param more whether there are more attributes after this one.
     * @param frequency the frequency of the attribute, 0..15
     * @return the flags
     */
    static final int makeShortcutFlags(final boolean more, final int frequency) {
        return (more ? FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_HAS_NEXT : 0)
                + (frequency & FormatSpec.FLAG_BIGRAM_SHORTCUT_ATTR_FREQUENCY);
    }

    private static final int writeParentAddress(final byte[] buffer, final int index,
            final int address, final FormatOptions formatOptions) {
        if (BinaryDictIOUtils.supportsDynamicUpdate(formatOptions)) {
            if (address == FormatSpec.NO_PARENT_ADDRESS) {
                buffer[index] = buffer[index + 1] = buffer[index + 2] = 0;
            } else {
                final int absAddress = Math.abs(address);
                assert(absAddress <= FormatSpec.SINT24_MAX);
                buffer[index] = (byte)((address < 0 ? FormatSpec.MSB8 : 0)
                        | ((absAddress >> 16) & 0xFF));
                buffer[index + 1] = (byte)((absAddress >> 8) & 0xFF);
                buffer[index + 2] = (byte)(absAddress & 0xFF);
            }
            return index + 3;
        } else {
            return index;
        }
    }

    /**
     * Write a PtNodeArray to memory. The PtNodeArray is expected to have its final position cached.
     *
     * @param dict the dictionary the node array is a part of (for relative offsets).
     * @param buffer the memory buffer to write to.
     * @param ptNodeArray the node array to write.
     * @param formatOptions file format options.
     * @return the address of the END of the node.
     */
    @SuppressWarnings("unused")
    private static int writePlacedNode(final FusionDictionary dict, byte[] buffer,
            final PtNodeArray ptNodeArray, final FormatOptions formatOptions) {
        // TODO: Make the code in common with BinaryDictIOUtils#writePtNode
        int index = ptNodeArray.mCachedAddressAfterUpdate;

        final int ptNodeCount = ptNodeArray.mData.size();
        final int countSize = getPtNodeCountSize(ptNodeArray);
        final int parentAddress = ptNodeArray.mCachedParentAddress;
        if (1 == countSize) {
            buffer[index++] = (byte)ptNodeCount;
        } else if (2 == countSize) {
            // We need to signal 2-byte size by setting the top bit of the MSB to 1, so
            // we | 0x80 to do this.
            buffer[index++] = (byte)((ptNodeCount >> 8) | 0x80);
            buffer[index++] = (byte)(ptNodeCount & 0xFF);
        } else {
            throw new RuntimeException("Strange size from getGroupCountSize : " + countSize);
        }
        int ptNodeAddress = index;
        for (int i = 0; i < ptNodeCount; ++i) {
            final PtNode ptNode = ptNodeArray.mData.get(i);
            if (index != ptNode.mCachedAddressAfterUpdate) {
                throw new RuntimeException("Bug: write index is not the same as the cached address "
                        + "of the node : " + index + " <> " + ptNode.mCachedAddressAfterUpdate);
            }
            ptNodeAddress += getNodeHeaderSize(ptNode, formatOptions);
            // Sanity checks.
            if (DBG && ptNode.mFrequency > FormatSpec.MAX_TERMINAL_FREQUENCY) {
                throw new RuntimeException("A node has a frequency > "
                        + FormatSpec.MAX_TERMINAL_FREQUENCY
                        + " : " + ptNode.mFrequency);
            }
            if (ptNode.mFrequency >= 0) ptNodeAddress += FormatSpec.PTNODE_FREQUENCY_SIZE;
            final int childrenOffset = null == ptNode.mChildren
                    ? FormatSpec.NO_CHILDREN_ADDRESS
                            : ptNode.mChildren.mCachedAddressAfterUpdate - ptNodeAddress;
            buffer[index++] =
                    makePtNodeFlags(ptNode, ptNodeAddress, childrenOffset, formatOptions);

            if (parentAddress == FormatSpec.NO_PARENT_ADDRESS) {
                index = writeParentAddress(buffer, index, parentAddress, formatOptions);
            } else {
                index = writeParentAddress(buffer, index, parentAddress
                        + (ptNodeArray.mCachedAddressAfterUpdate
                                - ptNode.mCachedAddressAfterUpdate),
                        formatOptions);
            }

            index = CharEncoding.writeCharArray(ptNode.mChars, buffer, index);
            if (ptNode.hasSeveralChars()) {
                buffer[index++] = FormatSpec.PTNODE_CHARACTERS_TERMINATOR;
            }
            if (ptNode.mFrequency >= 0) {
                buffer[index++] = (byte) ptNode.mFrequency;
            }

            final int shift;
            if (formatOptions.mSupportsDynamicUpdate) {
                shift = writeVariableSignedAddress(buffer, index, childrenOffset);
            } else {
                shift = writeVariableAddress(buffer, index, childrenOffset);
            }
            index += shift;
            ptNodeAddress += shift;

            // Write shortcuts
            if (null != ptNode.mShortcutTargets && !ptNode.mShortcutTargets.isEmpty()) {
                final int indexOfShortcutByteSize = index;
                index += FormatSpec.PTNODE_SHORTCUT_LIST_SIZE_SIZE;
                ptNodeAddress += FormatSpec.PTNODE_SHORTCUT_LIST_SIZE_SIZE;
                final Iterator<WeightedString> shortcutIterator =
                        ptNode.mShortcutTargets.iterator();
                while (shortcutIterator.hasNext()) {
                    final WeightedString target = shortcutIterator.next();
                    ++ptNodeAddress;
                    int shortcutFlags = makeShortcutFlags(shortcutIterator.hasNext(),
                            target.mFrequency);
                    buffer[index++] = (byte)shortcutFlags;
                    final int shortcutShift = CharEncoding.writeString(buffer, index, target.mWord);
                    index += shortcutShift;
                    ptNodeAddress += shortcutShift;
                }
                final int shortcutByteSize = index - indexOfShortcutByteSize;
                if (shortcutByteSize > 0xFFFF) {
                    throw new RuntimeException("Shortcut list too large");
                }
                buffer[indexOfShortcutByteSize] = (byte)(shortcutByteSize >> 8);
                buffer[indexOfShortcutByteSize + 1] = (byte)(shortcutByteSize & 0xFF);
            }
            // Write bigrams
            if (null != ptNode.mBigrams) {
                final Iterator<WeightedString> bigramIterator = ptNode.mBigrams.iterator();
                while (bigramIterator.hasNext()) {
                    final WeightedString bigram = bigramIterator.next();
                    final PtNode target =
                            FusionDictionary.findWordInTree(dict.mRootNodeArray, bigram.mWord);
                    final int addressOfBigram = target.mCachedAddressAfterUpdate;
                    final int unigramFrequencyForThisWord = target.mFrequency;
                    ++ptNodeAddress;
                    final int offset = addressOfBigram - ptNodeAddress;
                    int bigramFlags = makeBigramFlags(bigramIterator.hasNext(), offset,
                            bigram.mFrequency, unigramFrequencyForThisWord, bigram.mWord);
                    buffer[index++] = (byte)bigramFlags;
                    final int bigramShift = writeVariableAddress(buffer, index, Math.abs(offset));
                    index += bigramShift;
                    ptNodeAddress += bigramShift;
                }
            }

        }
        if (formatOptions.mSupportsDynamicUpdate) {
            buffer[index] = buffer[index + 1] = buffer[index + 2]
                    = FormatSpec.NO_FORWARD_LINK_ADDRESS;
            index += FormatSpec.FORWARD_LINK_ADDRESS_SIZE;
        }
        if (index != ptNodeArray.mCachedAddressAfterUpdate + ptNodeArray.mCachedSize) {
            throw new RuntimeException(
                    "Not the same size : written " + (index - ptNodeArray.mCachedAddressAfterUpdate)
                     + " bytes from a node that should have " + ptNodeArray.mCachedSize + " bytes");
        }
        return index;
    }

    /**
     * Dumps a collection of useful statistics about a list of PtNode arrays.
     *
     * This prints purely informative stuff, like the total estimated file size, the
     * number of PtNode arrays, of PtNodes, the repartition of each address size, etc
     *
     * @param ptNodeArrays the list of PtNode arrays.
     */
    private static void showStatistics(ArrayList<PtNodeArray> ptNodeArrays) {
        int firstTerminalAddress = Integer.MAX_VALUE;
        int lastTerminalAddress = Integer.MIN_VALUE;
        int size = 0;
        int ptNodes = 0;
        int maxNodes = 0;
        int maxRuns = 0;
        for (final PtNodeArray ptNodeArray : ptNodeArrays) {
            if (maxNodes < ptNodeArray.mData.size()) maxNodes = ptNodeArray.mData.size();
            for (final PtNode ptNode : ptNodeArray.mData) {
                ++ptNodes;
                if (ptNode.mChars.length > maxRuns) maxRuns = ptNode.mChars.length;
                if (ptNode.mFrequency >= 0) {
                    if (ptNodeArray.mCachedAddressAfterUpdate < firstTerminalAddress)
                        firstTerminalAddress = ptNodeArray.mCachedAddressAfterUpdate;
                    if (ptNodeArray.mCachedAddressAfterUpdate > lastTerminalAddress)
                        lastTerminalAddress = ptNodeArray.mCachedAddressAfterUpdate;
                }
            }
            if (ptNodeArray.mCachedAddressAfterUpdate + ptNodeArray.mCachedSize > size) {
                size = ptNodeArray.mCachedAddressAfterUpdate + ptNodeArray.mCachedSize;
            }
        }
        final int[] ptNodeCounts = new int[maxNodes + 1];
        final int[] runCounts = new int[maxRuns + 1];
        for (final PtNodeArray ptNodeArray : ptNodeArrays) {
            ++ptNodeCounts[ptNodeArray.mData.size()];
            for (final PtNode ptNode : ptNodeArray.mData) {
                ++runCounts[ptNode.mChars.length];
            }
        }

        MakedictLog.i("Statistics:\n"
                + "  total file size " + size + "\n"
                + "  " + ptNodeArrays.size() + " node arrays\n"
                + "  " + ptNodes + " PtNodes (" + ((float)ptNodes / ptNodeArrays.size())
                        + " PtNodes per node)\n"
                + "  first terminal at " + firstTerminalAddress + "\n"
                + "  last terminal at " + lastTerminalAddress + "\n"
                + "  PtNode stats : max = " + maxNodes);
        for (int i = 0; i < ptNodeCounts.length; ++i) {
            MakedictLog.i("    " + i + " : " + ptNodeCounts[i]);
        }
        MakedictLog.i("  Character run stats : max = " + maxRuns);
        for (int i = 0; i < runCounts.length; ++i) {
            MakedictLog.i("    " + i + " : " + runCounts[i]);
        }
    }

    /**
     * Writes a file header to an output stream.
     *
     * @param destination the stream to write the file header to.
     * @param dict the dictionary to write.
     * @param formatOptions file format options.
     */
    /* package */ static void writeDictionaryHeader(final OutputStream destination,
            final FusionDictionary dict, final FormatOptions formatOptions)
                    throws IOException, UnsupportedFormatException {
        final int version = formatOptions.mVersion;
        if (version < FormatSpec.MINIMUM_SUPPORTED_VERSION
                || version > FormatSpec.MAXIMUM_SUPPORTED_VERSION) {
            throw new UnsupportedFormatException("Requested file format version " + version
                    + ", but this implementation only supports versions "
                    + FormatSpec.MINIMUM_SUPPORTED_VERSION + " through "
                    + FormatSpec.MAXIMUM_SUPPORTED_VERSION);
        }

        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream(256);

        // The magic number in big-endian order.
        // Magic number for all versions.
        headerBuffer.write((byte) (0xFF & (FormatSpec.MAGIC_NUMBER >> 24)));
        headerBuffer.write((byte) (0xFF & (FormatSpec.MAGIC_NUMBER >> 16)));
        headerBuffer.write((byte) (0xFF & (FormatSpec.MAGIC_NUMBER >> 8)));
        headerBuffer.write((byte) (0xFF & FormatSpec.MAGIC_NUMBER));
        // Dictionary version.
        headerBuffer.write((byte) (0xFF & (version >> 8)));
        headerBuffer.write((byte) (0xFF & version));

        // Options flags
        final int options = makeOptionsValue(dict, formatOptions);
        headerBuffer.write((byte) (0xFF & (options >> 8)));
        headerBuffer.write((byte) (0xFF & options));
        final int headerSizeOffset = headerBuffer.size();
        // Placeholder to be written later with header size.
        for (int i = 0; i < 4; ++i) {
            headerBuffer.write(0);
        }
        // Write out the options.
        for (final String key : dict.mOptions.mAttributes.keySet()) {
            final String value = dict.mOptions.mAttributes.get(key);
            CharEncoding.writeString(headerBuffer, key);
            CharEncoding.writeString(headerBuffer, value);
        }
        final int size = headerBuffer.size();
        final byte[] bytes = headerBuffer.toByteArray();
        // Write out the header size.
        bytes[headerSizeOffset] = (byte) (0xFF & (size >> 24));
        bytes[headerSizeOffset + 1] = (byte) (0xFF & (size >> 16));
        bytes[headerSizeOffset + 2] = (byte) (0xFF & (size >> 8));
        bytes[headerSizeOffset + 3] = (byte) (0xFF & (size >> 0));
        destination.write(bytes);

        headerBuffer.close();
    }

    /**
     * Dumps a FusionDictionary to a file.
     *
     * @param destination the stream to write the dictionary body to.
     * @param dict the dictionary to write.
     * @param formatOptions file format options.
     */
    /* package */ static void writeDictionaryBody(final OutputStream destination,
            final FusionDictionary dict, final FormatOptions formatOptions) throws IOException {

        // Addresses are limited to 3 bytes, but since addresses can be relative to each node
        // array, the structure itself is not limited to 16MB. However, if it is over 16MB deciding
        // the order of the PtNode arrays becomes a quite complicated problem, because though the
        // dictionary itself does not have a size limit, each node array must still be within 16MB
        // of all its children and parents. As long as this is ensured, the dictionary file may
        // grow to any size.

        // Leave the choice of the optimal node order to the flattenTree function.
        MakedictLog.i("Flattening the tree...");
        ArrayList<PtNodeArray> flatNodes = flattenTree(dict.mRootNodeArray);

        MakedictLog.i("Computing addresses...");
        computeAddresses(dict, flatNodes, formatOptions);
        MakedictLog.i("Checking PtNode array...");
        if (DBG) checkFlatPtNodeArrayList(flatNodes);

        // Create a buffer that matches the final dictionary size.
        final PtNodeArray lastNodeArray = flatNodes.get(flatNodes.size() - 1);
        final int bufferSize = lastNodeArray.mCachedAddressAfterUpdate + lastNodeArray.mCachedSize;
        final byte[] buffer = new byte[bufferSize];
        int index = 0;

        MakedictLog.i("Writing file...");
        int dataEndOffset = 0;
        for (PtNodeArray nodeArray : flatNodes) {
            dataEndOffset = writePlacedNode(dict, buffer, nodeArray, formatOptions);
        }

        if (DBG) showStatistics(flatNodes);

        destination.write(buffer, 0, dataEndOffset);

        destination.close();
        MakedictLog.i("Done");
    }
}
